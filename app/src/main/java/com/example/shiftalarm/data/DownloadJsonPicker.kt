package com.example.shiftalarm.data

import android.content.Context
import android.content.Intent
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
 * This walks the real filesystem instead, through the ExternalStorageProvider tree
 * for the Download folder (`content://com.android.externalstorage.documents/...`),
 * so only files that actually exist are listed:
 *
 *  - Preferred tree: the Download-folder grant the user gives once via
 *    `OpenDocumentTree` (persisted with `takePersistableUriPermission`). On
 *    scoped-storage devices reading the Download folder really requires such a
 *    grant — without it the tree query/probe fails and the caller must prompt
 *    the user, not silently reopen the ghost-prone system picker.
 *  - Fallback tree: the well-known `primary:Download` tree, which several
 *    Android versions let apps browse without any grant. If even that fails,
 *    returns null.
 *
 * Returns the readable files newest-first; emptyList when the folder has no
 * readable .json files; null when no usable tree grant exists (the dialog then
 * shows a "Grant access" button instead of the system picker).
 */
object DownloadJsonPicker {

    /** Well-known ExternalStorageProvider tree for the Download folder. */
    const val DOWNLOAD_TREE_URI =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    private const val PREFS_NAME = "download_json_picker"
    private const val KEY_GRANTED_TREE = "granted_tree_uri"

    /** The Download-folder tree the user granted via OpenDocumentTree, if any. */
    fun grantedTree(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GRANTED_TREE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it) }

    /** Persists the user's folder grant so later imports never need the system picker. */
    fun saveGrantedTree(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // Some providers/OEMs refuse persistable grants; the grant still works
            // for this session and will simply be re-requested next time.
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GRANTED_TREE, treeUri.toString()).apply()
    }

    fun listDownloadJsonFiles(context: Context): List<DownloadJsonFile>? {
        grantedTree(context)?.let { granted ->
            // A granted tree is authoritative: whatever it returns is the real list.
            listWithTree(context, granted)?.let { return it }
            // Query failed (grant revoked / folder moved) — fall through and try
            // the well-known tree, then report null so the dialog re-prompts.
        }
        // No usable grant: try the well-known tree. If it lists candidates but
        // none can be opened, it is unusable on this device → null → dialog asks
        // the user for a grant (never the ghost-prone system picker).
        return listWithTree(context, Uri.parse(DOWNLOAD_TREE_URI), unreadableMeansUnusable = true)
    }

    /**
     * Lists .json files under [treeUri]. Null means the tree itself could not be
     * used. Non-null means the query worked; unreadable candidates are dropped
     * (result may be emptyList). When [unreadableMeansUnusable] and candidates
     * were found but none opened, returns null — the caller should ask for a grant.
     */
    private fun listWithTree(
        context: Context,
        treeUri: Uri,
        unreadableMeansUnusable: Boolean = false
    ): List<DownloadJsonFile>? {
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
        if (unreadableMeansUnusable && readable.isEmpty()) return null
        return readable.sortedByDescending { it.lastModifiedMillis }
    }
}