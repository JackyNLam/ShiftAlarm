package com.example.shiftalarm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Stores "schedule image" photos (pictures of the paper shift schedule) inside app
 * storage, resized to at most 1000px wide (EXIF rotation applied) so the originals
 * are cheap to keep, view and re-run through AI extraction.
 *
 * Full-res source is never kept: the saved JPEG is the resized one.
 */
class ScheduleImageStorage(context: Context) {

    private val contentResolver = context.contentResolver
    private val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Saves the image at [uri], returns the stored file name ("img_<millis>.jpg"). */
    fun saveImage(uri: Uri): String {
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

        // 3. Sample down close to target width, then decode.
        val sample = sampleSizeFor(effectiveWidth, TARGET_WIDTH)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: throw IllegalStateException("無法解碼圖片 / Cannot decode image")

        // 4. Rotate according to EXIF, then scale exactly to target width.
        val rotated = applyExifRotation(decoded, orientation)
        val finalWidth = if (rotated.width > TARGET_WIDTH) TARGET_WIDTH else rotated.width
        val finalHeight = (rotated.height.toLong() * finalWidth / rotated.width).toInt()
            .coerceAtLeast(1)
        val resized = if (finalWidth == rotated.width) rotated
        else Bitmap.createScaledBitmap(rotated, finalWidth, finalHeight, true)

        val name = "img_${System.currentTimeMillis()}.jpg"
        FileOutputStream(File(dir, name)).use { out ->
            resized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }

        // Recycle intermediate bitmaps we no longer need (identity checks avoid
        // recycling an instance that is still the final one).
        if (resized !== rotated) rotated.recycle()
        if (rotated !== decoded) decoded.recycle()
        return name
    }

    /** All stored image file names, newest first. */
    fun listImages(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()

    fun fileFor(name: String): File = File(dir, name)

    fun delete(name: String) {
        File(dir, name).delete()
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
        private const val TARGET_WIDTH = 1000
        private const val MAX_SOURCE_BYTES = 100 * 1024 * 1024
    }
}