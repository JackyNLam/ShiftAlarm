package com.example.shiftalarm.data

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

/** One real .json file found in the user's chosen import folder, with its content Uri. */
data class DownloadJsonFile(
    val name: String,
    val uri: Uri,
    val lastModifiedMillis: Long
)

/**
 * Lists .json files without the system picker, defaulting to Documents/ShiftAlarm.
 *
 * Ghost files (stale entries for deleted/overwritten files) are caused by the
 * MediaStore/ExternalStorageProvider index holding cached URI references after a
 * file is replaced or deleted. The system picker queries that index, not the
 * physical drive, so ghost entries survive until a background rescan.
 *
 * This class uses direct filesystem traversal ([File.listFiles]) as its primary
 * path on non-scoped-storage devices. On API 30+ devices with scoped storage,
 * [File.listFiles] returns null for directories like Documents/ShiftAlarm that
 * are not in a MediaStore collection. On those devices the user must grant
 * folder access once via [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE].
 * The granted tree URI is persisted in SharedPreferences and is used for all
 * future queries, producing readable content URIs.
 *
 * Fallback chain:
 *   Phase 1 — Direct filesystem listing of Documents/ShiftAlarm.
 *   Phase 2 — Persisted SAF tree URI (user-granted; must be granted once).
 *   Phase 3 — Direct filesystem listing of the Download folder.
 *   Phase 4 — Hardcoded Download ExternalStorageProvider tree query.
 *
 * Returns files newest-first; empty when no readable .json files exist.
 */
object DownloadJsonPicker {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOAD_TREE_URI =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private const val PREF_NAME = "DownloadJsonPicker"
    private const val KEY_PERSISTED_TREE_URI = "persisted_tree_uri"

    // ── Main listing entry point ───────────────────────────────────────────

    fun listDownloadJsonFiles(context: Context): List<DownloadJsonFile> {
        // Phase 1 — Direct filesystem listing of Documents/ShiftAlarm.
        // Auto-creates the folder so it is always available.
        val shiftAlarmDir = try {
            val docsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            )
            File(docsDir, "ShiftAlarm").also { it.mkdirs() }
        } catch (e: Exception) {
            null
        }

        if (shiftAlarmDir != null && shiftAlarmDir.isDirectory) {
            val jsonFiles = shiftAlarmDir.listFiles { f ->
                f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".json")
            }
            if (jsonFiles != null && jsonFiles.isNotEmpty()) {
                return jsonFiles
                    .map { file ->
                        val docId = "primary:Documents/ShiftAlarm/${file.name}"
                        val uri = DocumentsContract.buildDocumentUri(
                            EXTERNAL_STORAGE_AUTHORITY, docId
                        )
                        DownloadJsonFile(file.name, uri, file.lastModified())
                    }
                    .sortedByDescending { it.lastModifiedMillis }
            }
        }

        // Phase 2 — Persisted SAF tree URI (user-granted via ACTION_OPEN_DOCUMENT_TREE).
        // The user selects Documents/ShiftAlarm once; subsequent app launches use this
        // URI via scanTreeViaDocumentsContract() without requiring MANAGE_EXTERNAL_STORAGE.
        val persistedUri = getPersistedTreeUri(context)
        if (persistedUri != null) {
            // Only scan the user-selected folder — DO NOT fall through to Download,
            // which would show files from the wrong location.
            return scanTreeViaDocumentsContract(context, persistedUri)
        }

        // Phase 3 — Fallback to Download folder (no persisted URI yet).
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

        // Phase 4 — Hardcoded Download tree URI (may work on some devices).
        return listViaProvider(context)
    }

    // ── SAF tree URI helpers ──────────────────────────────────────────────────

    /**
     * Persist a user-selected SAF tree URI so it survives app restarts.
     *
     * Takes a persistable read permission on the URI so [buildDocumentUriUsingTree]
     * URIs stay readable across reboots. Call this right after the user picks a
     * folder via [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE].
     */
    fun savePersistedTreeUri(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Without persistable permission the URI may not survive restarts,
            // but the scan still works within the current activity lifecycle.
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERSISTED_TREE_URI, treeUri.toString())
            .apply()
    }

    /** Returns the persisted SAF tree URI, or null if none was granted. */
    fun getPersistedTreeUri(context: Context): Uri? {
        val uriStr = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PERSISTED_TREE_URI, null) ?: return null
        return try { Uri.parse(uriStr) } catch (_: Exception) { null }
    }

    /** Clears the persisted SAF tree URI. */
    fun clearPersistedTreeUri(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PERSISTED_TREE_URI)
            .apply()
    }

    // ── SAF tree traversal ──────────────────────────────────────────────────

    /**
     * Lists .json files in a user-selected folder via SAF tree traversal.
     *
     * Can be called directly with a freshly-picked URI (without pre-persisting).
     * [savePersistedTreeUri] must be called separately if the caller wants the
     * URI to survive process restarts.
     */
    fun listJsonFilesFromTreeUri(context: Context, treeUri: Uri): List<DownloadJsonFile> {
        return scanTreeViaDocumentsContract(context, treeUri)
    }

    /**
     * Shared SAF tree scanner used by all tree-URI-based phases and
     * [listJsonFilesFromTreeUri].
     */
    private fun scanTreeViaDocumentsContract(context: Context, treeUri: Uri): List<DownloadJsonFile> {
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

    /** Fallback: query the Download folder through the ExternalStorageProvider tree. */
    private fun listViaProvider(context: Context): List<DownloadJsonFile> {
        val treeUri = Uri.parse(DOWNLOAD_TREE_URI)
        return scanTreeViaDocumentsContract(context, treeUri)
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