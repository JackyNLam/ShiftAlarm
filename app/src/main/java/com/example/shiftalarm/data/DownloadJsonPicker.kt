package com.example.shiftalarm.data

import android.content.Context
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
 * Ghost files (stale entries for deleted/overwritten files) can appear because the
 * ExternalStorageProvider cursor may hold cached URI references or because MediaStore
 * indexing is out of sync. This class uses a two-phase approach to be ghost-proof:
 *
 * **Phase 1 — File API (ground truth):** resolves each provider document ID to an
 * absolute path under the primary external-storage root and checks [File.exists].
 * This reads the real inode and is immune to provider caching, MediaStore indexing
 * delays, and SAF sandbox copies. Files that no longer exist on disk are dropped —
 * the earlier ghost version of a same-name file cannot survive. Works for files the
 * app created (exported schedule JSONs) on Android 11+ scoped storage.
 *
 * **Phase 2 — Readability probe + dedup (fallback):** when the File API is blocked by
 * scoped storage (all [File.exists] checks return false because the files are owned
 * by other apps), falls back to an openFileDescriptor probe to drop unreadable stale
 * rows, then dedupes by name keeping the newest entry.
 *
 * Both phases dedup by file name (case-insensitive) keeping the newest, so even if
 * two provider rows share a display name, only the latest one is shown.
 *
 * Returns files newest-first; emptyList when no readable .json files exist; null
 * when the well-known tree is unusable (caller should fall back to system picker).
 */
object DownloadJsonPicker {

    /** Well-known ExternalStorageProvider tree for the Download folder (no grant needed). */
    private const val DOWNLOAD_TREE_URI =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    private class Candidate(
        val name: String,
        val uri: Uri,
        val providerModified: Long,
        val docId: String
    )

    fun listDownloadJsonFiles(context: Context): List<DownloadJsonFile>? {
        val treeUri = Uri.parse(DOWNLOAD_TREE_URI)
        val resolver = context.contentResolver
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

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
                    candidates.add(Candidate(name ?: docId, docUri, modified, docId))
                }
            }
        } catch (e: Exception) {
            return null
        }

        if (candidates.isEmpty()) return emptyList()

        // Phase 1: File API — reads actual inodes, immune to provider caching / indexing delay.
        val externalRoot = try {
            Environment.getExternalStorageDirectory().absolutePath
        } catch (e: Exception) {
            null
        }

        if (externalRoot != null) {
            val fileVerified = mutableListOf<DownloadJsonFile>()
            for (c in candidates) {
                val path = docIdToFilePath(c.docId, externalRoot) ?: continue
                val file = File(path)
                if (file.exists() && file.isFile) {
                    fileVerified.add(DownloadJsonFile(c.name, c.uri, file.lastModified()))
                }
            }
            if (fileVerified.isNotEmpty()) {
                return dedupByNameKeepNewest(fileVerified)
            }
        }

        // Phase 2: scoped storage blocked the File API — fall back to readability probe.
        val readable = candidates.filter { c ->
            try {
                resolver.openFileDescriptor(c.uri, "r")?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        }.map { DownloadJsonFile(it.name, it.uri, it.providerModified) }

        return dedupByNameKeepNewest(readable)
    }

    /** Converts an ExternalStorageProvider document ID (e.g. `primary:Download/x.json`) to a path. */
    private fun docIdToFilePath(docId: String, externalRoot: String): String? {
        if (!docId.startsWith("primary:")) return null
        return "$externalRoot/${docId.removePrefix("primary:")}"
    }

    /** Dedupes by name (case-insensitive) keeping the newest, sorted newest-first. */
    private fun dedupByNameKeepNewest(files: List<DownloadJsonFile>): List<DownloadJsonFile> =
        files
            .groupBy { it.name.lowercase(Locale.ROOT) }
            .mapValues { (_, list) -> list.maxByOrNull { it.lastModifiedMillis }!! }
            .values
            .sortedByDescending { it.lastModifiedMillis }
}
