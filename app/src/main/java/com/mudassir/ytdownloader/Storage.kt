package com.mudassir.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
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

    /** Copies a finished file into Downloads/YTDownloader[/subFolder]. Returns the folder shown to the user. */
    fun saveToDownloads(ctx: Context, src: File, fileName: String, mime: String, subFolder: String?): String {
        val rel = listOfNotNull(Environment.DIRECTORY_DOWNLOADS, FOLDER, subFolder?.let(::sanitize))
            .joinToString("/")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file in Downloads")
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), rel).apply { mkdirs() }
            val dest = File(dir, fileName)
            src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime), null)
        }
        return rel
    }
}
