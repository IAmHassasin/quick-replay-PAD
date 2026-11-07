package dev.quickreplay.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only access to downloaded update APKs for the system package installer, like AndroidX
 * FileProvider (the app has no AndroidX dependency). Not exported: the installer only gets a
 * one-off read grant on the URI it is opened with. Only `quick-replay-pad-<version>.apk` files
 * in the update cache directory are served.
 */
class ApkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun getType(uri: Uri): String = MIME_APK

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileFor(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun fileFor(uri: Uri): File {
        val context = context ?: throw FileNotFoundException()
        val name = uri.pathSegments.singleOrNull()
        if (name == null || !APK_NAME.matches(name)) throw FileNotFoundException("Unknown file")
        val file = File(UpdateController.apkDir(context), name)
        if (!file.isFile) throw FileNotFoundException("Unknown file")
        return file
    }

    companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
        private val APK_NAME = Regex("^quick-replay-pad-\\d{1,9}\\.\\d{1,9}\\.\\d{1,9}\\.apk$")

        fun authority(context: Context) = "${context.packageName}.updates"

        fun uriFor(context: Context, apk: File): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(apk.name).build()
    }
}
