package com.mudassir.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File

object Storage {

    const val FOLDER = "YTDownloader"

    fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        -1L
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(120).ifEmpty { "video" }

    /** "Download/YTDownloader[/subFolder]" */
    fun relativePath(subFolder: String?): String =
        listOfNotNull(Environment.DIRECTORY_DOWNLOADS, FOLDER, subFolder?.let(::sanitize)).joinToString("/")

    /**
     * Uri of an existing file with this name in the target folder, or null.
     * On Android 10+ this only sees files this install of the app saved.
     */
    fun find(ctx: Context, fileName: String, subFolder: String?): Uri? {
        val rel = relativePath(subFolder)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?) AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf("$rel/", rel, fileName),
                null
            )?.use { c ->
                if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                else null
            }
        } else {
            @Suppress("DEPRECATION")
            File(File(Environment.getExternalStorageDirectory(), rel), fileName).takeIf { it.exists() }?.let(Uri::fromFile)
        }
    }

    /** True if the saved file behind [uri] still exists (the user may have deleted it). */
    fun exists(ctx: Context, uri: String?): Boolean {
        if (uri == null) return false
        val u = Uri.parse(uri)
        return if (u.scheme == "file") File(u.path ?: return false).exists()
        else runCatching { ctx.contentResolver.openFileDescriptor(u, "r")?.use { true } ?: false }.getOrDefault(false)
    }

    /** Copies a finished file into Downloads/YTDownloader[/subFolder]. Returns the saved file's Uri. */
    fun saveToDownloads(ctx: Context, src: File, fileName: String, mime: String, subFolder: String?): Uri {
        val rel = relativePath(subFolder)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), rel).apply { mkdirs() }
            val dest = File(dir, fileName)
            src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime), null)
            Uri.fromFile(dest)
        }
    }
}
