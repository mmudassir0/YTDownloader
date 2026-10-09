package com.mudassir.ytdownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/** Hands files to Android's DownloadManager, which shows progress in notifications. */
object FileSaver {

    const val FOLDER = "YTDownloader"

    fun enqueue(
        context: Context,
        option: DownloadOption,
        title: String,
        subFolder: String? = null
    ): Long {
        val fileName = "${sanitize(title)}.${option.extension}"
        val relPath = listOfNotNull(FOLDER, subFolder?.let(::sanitize), fileName).joinToString("/")

        val request = DownloadManager.Request(Uri.parse(option.url))
            .setTitle(title)
            .setDescription(option.label)
            .addRequestHeader("User-Agent", OkHttpDownloader.USER_AGENT)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, relPath)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(120).ifEmpty { "video" }
}
