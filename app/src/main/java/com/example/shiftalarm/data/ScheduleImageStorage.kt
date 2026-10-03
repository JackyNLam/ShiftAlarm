package com.example.shiftalarm.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Stores "schedule image" photos (pictures of the paper shift schedule) inside app
 * storage. Images are kept at original resolution unless the longer side exceeds
 * MAX_SIDE (2000px), in which case they are downscaled so the longer side becomes
 * exactly 2000px (EXIF rotation applied). The picker's original file name and
 * modified time are remembered separately so the viewer can show them instead of
 * the internal "img_<millis>.jpg" name / the import time.
 *
 * Full-res source is never kept: the saved JPEG is the possibly-downscaled one.
 */
class ScheduleImageStorage(context: Context) {

    private val contentResolver = context.contentResolver
    private val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    private val namePrefs =
        context.getSharedPreferences(PREFS_NAMES, Context.MODE_PRIVATE)
    private val modifiedPrefs =
        context.getSharedPreferences(PREFS_MODIFIED, Context.MODE_PRIVATE)

    /** Saves the image at [uri], returns the stored file name ("img_<millis>.jpg"). */
    fun saveImage(uri: Uri): String {
        // Capture the original name + modified time BEFORE consuming the stream:
        // picker content URIs can be one-shot, and re-querying after the read can
        // come back empty for some providers (e.g. Google Photos).
        val meta = querySourceMeta(uri)
        // Read the source once into memory: picker content URIs can be one-shot
        // streams, so reuse the same bytes for bounds, EXIF and the final decode
        // instead of re-opening the URI (also sidesteps decodeStream quirks).
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("無法開啟圖片 / Cannot open image")
        if (bytes.size > MAX_SOURCE_BYTES) {
            throw IllegalArgumentException("圖片太大 / Image too large")
        }

        // 1. Read dimensions without decoding, to pick a safe sampling factor.
        //    (Bounds-only decoding returns null even on success — check the size.)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("無法解碼圖片 / Cannot decode image")
        }

        // 2. EXIF orientation (phone photos are often rotated 90°).
        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            // unreadable EXIF — treat as upright
        }
        val swapped = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        val effectiveWidth = if (swapped) bounds.outHeight else bounds.outWidth
        val effectiveHeight = if (swapped) bounds.outWidth else bounds.outHeight
        val effectiveMaxSide = maxOf(effectiveWidth, effectiveHeight)

        // 3. Keep the original resolution unless the longer side exceeds MAX_SIDE;
        //    only then sample down (decoded long side is at most ~2x MAX_SIDE) and
        //    later scale precisely to MAX_SIDE.
        val sample = if (effectiveMaxSide > MAX_SIDE) {
            sampleSizeFor(effectiveMaxSide, MAX_SIDE)
        } else 1
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: throw IllegalStateException("無法解碼圖片 / Cannot decode image")

        // 4. Rotate according to EXIF, then scale only if the longer side > MAX_SIDE
        //    so the longer side becomes exactly MAX_SIDE.
        val rotated = applyExifRotation(decoded, orientation)
        val resized = if (effectiveMaxSide > MAX_SIDE) {
            val longSide = maxOf(rotated.width, rotated.height)
            val scale = MAX_SIDE.toFloat() / longSide
            val finalWidth = (rotated.width * scale).toInt().coerceAtLeast(1)
            val finalHeight = (rotated.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(rotated, finalWidth, finalHeight, true)
        } else rotated

        val name = "img_${System.currentTimeMillis()}.jpg"
        FileOutputStream(File(dir, name)).use { out ->
            resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }

        // Remember the picker's original file name and modified time so the viewer
        // can show them (internal names are img_<millis>.jpg and meaningless).
        val displayName = meta.displayName
        if (!displayName.isNullOrBlank()) {
            namePrefs.edit().putString(name, displayName.trim()).apply()
        }
        meta.modifiedMillis?.takeIf { it > 0 }?.let { m ->
            modifiedPrefs.edit().putLong(name, m).apply()
        }

        // Recycle intermediate bitmaps we no longer need (identity checks avoid
        // recycling an instance that is still the final one).
        if (resized !== rotated) rotated.recycle()
        if (rotated !== decoded) decoded.recycle()
        return name
    }

    /** All stored image file names, newest (by original modified time) first. */
    fun listImages(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedWith(compareByDescending<File> { f ->
                // Prefer the source file's modified time as captured at import;
                // images saved before it was recorded fall back to the import
                // time, which is what the internal name encodes.
                modifiedPrefs.getLong(f.name, millisFromName(f.name))
            })
            ?.map { it.name }
            ?: emptyList()

    private fun millisFromName(storedName: String): Long =
        storedName.removePrefix("img_").removeSuffix(".jpg").toLongOrNull() ?: 0L

    fun fileFor(name: String): File = File(dir, name)

    fun delete(name: String) {
        File(dir, name).delete()
        namePrefs.edit().remove(name).apply()
        modifiedPrefs.edit().remove(name).apply()
    }

    private data class SourceMeta(val displayName: String?, val modifiedMillis: Long?)

    /** Best-effort original name + last-modified (millis) of the picked file. */
    private fun querySourceMeta(uri: Uri): SourceMeta {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(uri.path ?: "")
            if (f.exists()) return SourceMeta(f.name, f.lastModified())
            return SourceMeta(null, null)
        }
        var displayName = queryColumnString(uri, OpenableColumns.DISPLAY_NAME)
        var modifiedMillis = queryModifiedMillis(uri)
        // Some providers (notably OEM photo pickers) reject column-projection
        // queries or hide the standard column names, leaving both null. As a
        // last resort, query with the provider's default projection and scan
        // the returned column names for anything name- or modified-time-like.
        if (displayName.isNullOrBlank() || modifiedMillis == null) {
            val scanned = querySourceMetaByScan(uri)
            if (scanned != null) {
                if (displayName.isNullOrBlank() && !scanned.displayName.isNullOrBlank()) {
                    displayName = scanned.displayName
                }
                if (modifiedMillis == null && scanned.modifiedMillis != null) {
                    modifiedMillis = scanned.modifiedMillis
                }
            }
        }
        // The system photo picker (content://media/picker/.../media/<id>) hides
        // the real file name: DISPLAY_NAME comes back as the MediaStore _ID or a
        // synthesized "<id>.jpg" (issuetracker 268079113). Map the embedded id
        // back to the real MediaStore row when media-read access is granted, and
        // let those ground-truth values override the synthesized ones.
        resolveMediaStoreMeta(uri)?.let { real ->
            if (!real.displayName.isNullOrBlank() &&
                (displayName.isNullOrBlank() || looksSynthesized(displayName))
            ) {
                displayName = real.displayName
            }
            if (real.modifiedMillis != null && (modifiedMillis == null || modifiedMillis <= 0)) {
                modifiedMillis = real.modifiedMillis
            }
        }
        return SourceMeta(displayName, modifiedMillis)
    }

    /** True when [name] looks like a provider-synthesized "<id>.jpg" instead of a real file name. */
    private fun looksSynthesized(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val stem = name.substringBeforeLast('.', name).trim()
        return stem.isNotEmpty() && stem.all { it.isDigit() }
    }

    /**
     * Extracts a MediaStore row id from picker/media/document URIs:
     * content://media/picker/<session>/.../media/61479 -> 61479,
     * content://media/external/images/media/61479 -> 61479,
     * content://.../document/image%3A61479 -> 61479.
     */
    private fun mediaIdFromUri(uri: Uri): Long? {
        val segments = uri.pathSegments ?: return null
        for (i in segments.indices.reversed()) {
            val seg = segments[i]
            val pure = seg.toLongOrNull()
            if (pure != null && pure > 0 &&
                (i == segments.lastIndex || segments.getOrNull(i - 1) == "media")
            ) {
                return pure
            }
            val afterColon = Uri.decode(seg).substringAfterLast(':').toLongOrNull()
            if (afterColon != null && afterColon > 0) return afterColon
        }
        return null
    }

    /**
     * Maps the MediaStore id embedded in photo-picker URIs back to the real row,
     * returning the true file name + modified time. Requires media-read access
     * (READ_MEDIA_IMAGES on 13+, READ_EXTERNAL_STORAGE below); the photo picker
     * itself is permissionless and grants no access to MediaStore.
     */
    private fun resolveMediaStoreMeta(uri: Uri): SourceMeta? {
        val id = mediaIdFromUri(uri) ?: return null
        if (!hasMediaReadAccess()) return null
        return try {
            val projection = arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED
            )
            val queryUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            contentResolver.query(
                queryUri,
                projection,
                "${MediaStore.Images.Media._ID}=?",
                arrayOf(id.toString()),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = readString(cursor, MediaStore.Images.Media.DISPLAY_NAME)
                val modified = readLong(cursor, MediaStore.Images.Media.DATE_MODIFIED)?.let { raw ->
                    if (raw > 0) {
                        // MediaStore date_modified is epoch seconds -> millis
                        if (raw < 10_000_000_000L) raw * 1000 else raw
                    } else null
                }
                SourceMeta(name, modified)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Whether the app may read other apps' media from MediaStore. */
    private fun hasMediaReadAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
        else ->
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Last-resort metadata lookup for pickers that hide the standard
     * DISPLAY_NAME / last_modified / date_modified columns. Queries with the
     * provider's default projection and scans the returned column names for
     * anything that looks like a file name or a modified time.
     */
    private fun querySourceMetaByScan(uri: Uri): SourceMeta? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            var displayName: String? = null
            var modifiedMillis: Long? = null
            for (col in cursor.columnNames) {
                val low = col.lowercase(Locale.US)
                if (displayName == null &&
                    (low == "_display_name" || low == "display_name" ||
                        low == "name" || low == "title" || low == "file_name")
                ) {
                    displayName = readString(cursor, col)
                }
                if (modifiedMillis == null &&
                    (low.contains("modified") || low == "last_modified" ||
                        low == "date_modified")
                ) {
                    // epoch seconds -> millis, like queryModifiedMillis
                    val v = readLong(cursor, col)
                    if (v != null && v > 0) {
                        modifiedMillis = if (v < 10_000_000_000L) v * 1000 else v
                    }
                }
                if (displayName != null && modifiedMillis != null) break
            }
            SourceMeta(displayName, modifiedMillis)
        }
    } catch (_: Exception) {
        null
    }

    private fun readString(cursor: Cursor, column: String): String? {
        val idx = cursor.getColumnIndex(column)
        return if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
    }

    private fun readLong(cursor: Cursor, column: String): Long? {
        val idx = cursor.getColumnIndex(column)
        if (idx < 0 || cursor.isNull(idx)) return null
        return if (cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER) cursor.getLong(idx)
        else cursor.getString(idx)?.trim()?.toLongOrNull()
    }

    /**
     * Documents/MediaStore providers expose the picked file's modified time
     * differently: DocumentsContract.last_modified is epoch millis, MediaStore
     * date_modified is epoch seconds. Try both, convert to millis.
     */
    private fun queryModifiedMillis(uri: Uri): Long? {
        try {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val raw = if (cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER)
                            cursor.getLong(idx)
                        else cursor.getString(idx)?.toLongOrNull()
                        if (raw != null && raw > 0) return raw
                    }
                }
            }
        } catch (_: Exception) {
            // provider does not expose this column — try MediaStore below
        }
        try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val raw = if (cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER)
                            cursor.getLong(idx)
                        else cursor.getString(idx)?.toLongOrNull()
                        if (raw != null && raw > 0) {
                            // seconds since epoch -> millis
                            return if (raw < 10_000_000_000L) raw * 1000 else raw
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // not queryable — report nothing
        }
        return null
    }

    /** Best-effort display name of the picked file (null if the provider hides it). */
    private fun queryColumnString(uri: Uri, column: String): String? = try {
        contentResolver.query(
            uri,
            arrayOf(column),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(column)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= targetWidth) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    private fun applyExifRotation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    companion object {
        const val DIR_NAME = "schedule_images"
        private const val PREFS_NAMES = "schedule_image_names"
        private const val PREFS_MODIFIED = "schedule_image_modified"
        private const val MAX_SIDE = 2000
        private const val MAX_SOURCE_BYTES = 100 * 1024 * 1024

        /** Original picker name for [storedName], falling back to [storedName]. */
        fun originalNameOf(context: Context, storedName: String): String {
            val prefs = context.getSharedPreferences(PREFS_NAMES, Context.MODE_PRIVATE)
            return prefs.getString(storedName, null) ?: storedName
        }

        /**
         * Original picker modified time (epoch millis) for [storedName], or null
         * when it was not recorded (images imported before this was added).
         */
        fun originalModifiedOf(context: Context, storedName: String): Long? {
            val prefs = context.getSharedPreferences(PREFS_MODIFIED, Context.MODE_PRIVATE)
            return if (prefs.contains(storedName)) {
                prefs.getLong(storedName, 0L).takeIf { it > 0 }
            } else null
        }
    }
}