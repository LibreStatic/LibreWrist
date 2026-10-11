package io.openshm.patcher.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException

/**
 * [BackupFiles] in `Documents/OpenSHM` through MediaStore. On Android 10+ an app sees the files
 * it created without any storage permission, and the file stays after an uninstall,
 * which is the point. Only a public digest is in the file name.
 */
class MediaStoreBackupFiles(context: Context) : BackupFiles {
    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Files.getContentUri("external")
    private val relativePath = Environment.DIRECTORY_DOCUMENTS + "/OpenSHM"

    private class Row(val uri: Uri, val pending: Boolean)

    private fun find(name: String): Row? {
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(name, relativePath, "$relativePath/"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING), args, null)?.use { c ->
            if (c.moveToFirst()) return Row(ContentUris.withAppendedId(collection, c.getLong(0)), c.getInt(1) != 0)
        }
        return null
    }

    /** True only for a finished file; an interrupted write left pending counts as missing. */
    override fun exists(name: String): Boolean = find(name)?.pending == false

    override fun write(name: String, bytes: ByteArray) {
        // Always a new pending row: an existing file with the same name is never opened, so it cannot be
        // truncated or hidden (MediaStore renames the new one if the name is taken).
        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: throw IOException("the backup file cannot be created")
        try {
            (resolver.openOutputStream(uri, "w") ?: throw IOException("the backup file cannot be opened")).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null)
        } catch (e: Exception) {
            // Only the row created here.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private companion object {
        const val MIME = "application/x-pkcs12"
    }
}
