package com.example.shiftalarm.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
 * The system picker (ACTION_OPEN_DOCUMENT) is backed by MediaStore/DownloadProvider
 * rows and can keep showing entries for files that were already deleted ("ghost"
 * files — e.g. an exported JSON the user removed still appears in the picker).
 *
 * This walks the real filesystem instead, via the well-known ExternalStorageProvider
 * tree for the Download folder (`content://com.android.externalstorage.documents/...`),
 * so only files that actually exist are listed. The walk is flat (direct children of
 * `primary:Download` only), so files with the same name in other folders cannot leak
 * into the list. No storage permission is required for this traversal on standard
 * Android builds.
 *
 * Ghost-proofing (handles "the earlier version of the same-name file keeps coming up"):
 *  - Only files that can actually be opened right now are kept (readability probe via
 *    openFileDescriptor), so deleted/stale provider rows are dropped.
 *  - The result is deduped by file name (case-insensitive), keeping the newest entry,
 *    so any duplicate provider rows or same-name copies collapse to the latest one.
 *
 * Returns the files newest-first; emptyList when the folder has no readable .json
 * files; null when the well-known tree is unusable on this device (callers should
 * then fall back to the system picker).
 */
object DownloadJsonPicker {

    /** Well-known ExternalStorageProvider tree for the Download folder (no grant needed). */
    private const val DOWNLOAD_TREE_URI =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    fun listDownloadJsonFiles(context: Context): List<DownloadJsonFile>? {
        val treeUri = Uri.parse(DOWNLOAD_TREE_URI)
        val resolver = context.contentResolver
        val treeDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val found = mutableListOf<DownloadJsonFile>()
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
                    found.add(DownloadJsonFile(name ?: docId, docUri, modified))
                }
            }
        } catch (e: Exception) {
            return null
        }
        if (found.isEmpty()) return emptyList()

        // Ghost-proof: keep only files that can actually be opened right now.
        val readable = found.filter { f ->
            try {
                resolver.openFileDescriptor(f.uri, "r")?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        }

        // Dedupe by name (case-insensitive), keeping the newest entry per name, so an
        // earlier version of a same-name file cannot appear alongside the latest one.
        val deduped = readable
            .groupBy { it.name.lowercase(Locale.ROOT) }
            .mapValues { (_, list) -> list.maxByOrNull { it.lastModifiedMillis }!! }
            .values
            .sortedByDescending { it.lastModifiedMillis }

        return deduped
    }
}
