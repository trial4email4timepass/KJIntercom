package com.khalsa.intercom

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Shares recorded Public Broadcast files (read-only) with other apps. */
class BroadcastFileProvider : ContentProvider() {
    companion object {
        @JvmStatic
        fun uriFor(ctx: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(ctx.packageName + ".broadcast").appendPath(file.name).build()
    }

    private fun broadcastDir(): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        return File(ctx.cacheDir, "broadcast")
    }

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no file name")
        val dir = broadcastDir()
        val f = File(dir, name)
        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
            throw FileNotFoundException("outside broadcast dir")
        }
        return f
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri)
        if (!f.exists()) throw FileNotFoundException(f.name)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "audio/mp4"
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
