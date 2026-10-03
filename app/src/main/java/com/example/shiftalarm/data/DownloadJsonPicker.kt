package com.example.shiftalarm.data

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

/** One real .json file found in the device's Download folder, with its content Uri. */
data class DownloadJsonFile(
    val name: String,
    val uri: Uri,
    val lastModifiedMillis: Long
)

/**
 * Lists .json files in the Download folder without the system picker.
 *
 * Ghost files (stale entries for deleted/overwritten files) are caused by the
 * MediaStore/ExternalStorageProvider index holding cached URI references after a
 * file is replaced or deleted. The system picker queries that index, not the
 * physical drive, so ghost entries survive until a background rescan.
 *
 * This class uses direct filesystem traversal ([File.listFiles]) as its primary
 * path. It reads actual directory entries on disk, so deleted files disappear
 * immediately — the same behaviour as the "Internal Storage" tab in the system
 * file manager. Content URIs are built from the filesystem paths so the caller
 * can still open them through [android.content.ContentResolver].
 *
 * Falls back to an ExternalStorageProvider tree query + readability probe on
 * devices where scoped storage blocks the File API (Android 13+, API 33+).
 *
 * Returns files newest-first; empty when no readable .json files exist.
 */
object DownloadJsonPicker {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOAD_TREE_URI =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    fun listDownloadJsonFiles(context: Context): List<DownloadJsonFile> {
        // Phase 1 — Direct filesystem listing.
        // Reads real directory entries, immune to MediaStore / provider caching.
        val downloadDir = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (e: Exception) {
            null
        }

        if (downloadDir != null && downloadDir.isDirectory) {
            val jsonFiles = downloadDir.listFiles { f ->
                f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".json")
            }
            if (jsonFiles != null && jsonFiles.isNotEmpty()) {
                return jsonFiles
                    .map { file ->
                        val docId = "primary:Download/${file.name}"
                        val uri = DocumentsContract.buildDocumentUri(
                            EXTERNAL_STORAGE_AUTHORITY, docId
                        )
                        DownloadJsonFile(file.name, uri, file.lastModified())
                    }
                    .sortedByDescending { it.lastModifiedMillis }
            }
        }

        // Phase 2 — Scoped storage blocked the File API.
        // Fall back to ExternalStorageProvider tree query with readability probe.
        return listViaProvider(context)
    }

    private fun listViaProvider(context: Context): List<DownloadJsonFile> {
        val treeUri = Uri.parse(DOWNLOAD_TREE_URI)
        val resolver = context.contentResolver
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return emptyList()
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

        data class Candidate(
            val name: String,
            val uri: Uri,
            val modified: Long
        )

        val candidates = mutableListOf<Candidate>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    val isJson = mime?.equals("application/json", ignoreCase = true) == true ||
                        name?.lowercase(Locale.ROOT)?.endsWith(".json") == true
                    if (!isJson) continue
                    val docId = if (idCol >= 0) cursor.getString(idCol) else null
                    if (docId.isNullOrBlank()) continue
                    val modified = if (modCol >= 0 && !cursor.isNull(modCol)) cursor.getLong(modCol) else 0L
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    candidates.add(Candidate(name ?: docId, docUri, modified))
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        if (candidates.isEmpty()) return emptyList()

        val readable = candidates.filter { c ->
            try {
                resolver.openFileDescriptor(c.uri, "r")?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        }.map { DownloadJsonFile(it.name, it.uri, it.modified) }

        return dedupByNameKeepNewest(readable)
    }

    /** Dedupes by name (case-insensitive) keeping the newest, sorted newest-first. */
    private fun dedupByNameKeepNewest(files: List<DownloadJsonFile>): List<DownloadJsonFile> =
        files
            .groupBy { it.name.lowercase(Locale.ROOT) }
            .mapValues { (_, list) -> list.maxByOrNull { it.lastModifiedMillis }!! }
            .values
            .sortedByDescending { it.lastModifiedMillis }

    /**
     * Forces MediaStore to rescan the Download folder before launching the system
     * file picker, so deleted files ("ghosts") are dropped from the provider index.
     *
     * Call this right before [ActivityResultContracts.OpenDocument].launch().
     * [onComplete] runs on the main thread after the scan is done — launch the
     * picker inside it.
     */
    fun refreshDownloadsCache(context: Context, onComplete: () -> Unit) {
        val downloadDir = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (e: Exception) {
            null
        }
        if (downloadDir != null && downloadDir.isDirectory) {
            val files = downloadDir.listFiles()
            if (!files.isNullOrEmpty()) {
                val filePaths = files.map { it.absolutePath }.toTypedArray()
                MediaScannerConnection.scanFile(context, filePaths, null) { _, _ ->
                    onComplete()
                }
                return
            }
        }
        onComplete()
    }
}