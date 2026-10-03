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
 * so only files that actually exist are listed. No storage permission is required
 * for this traversal on standard Android builds.
 *
 * Returns the files newest-first, or null when the well-known tree is unusable on
 * this device (callers should then fall back to the system picker).
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

        // Ghost-proof: keep only files that can actually be opened right now. If we
        // found candidates but none is readable, the tree traversal is not usable on
        // this device — make the caller fall back to the system picker.
        val readable = found.filter { f ->
            try {
                resolver.openFileDescriptor(f.uri, "r")?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        }
        if (readable.isEmpty()) return null
        return readable.sortedByDescending { it.lastModifiedMillis }
    }
}