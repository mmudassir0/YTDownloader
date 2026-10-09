package com.mudassir.ytdownloader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads YouTube media in 10 MB pieces (the "range" URL parameter), the same way
 * the official apps do. Plain full-file requests get throttled or rejected for HD tracks.
 */
object ChunkDownloader {

    private const val CHUNK = 10L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** @param onBytes called with bytes written so far for this file. */
    suspend fun download(url: String, dest: File, onBytes: (Long) -> Unit) {
        val chunked = url.contains("googlevideo.com")
        var written = 0L
        FileOutputStream(dest).use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val reqUrl = if (chunked) "$url&range=$written-${written + CHUNK - 1}" else url
                val got = fetchWithRetry(reqUrl) { input ->
                    val buf = ByteArray(64 * 1024)
                    var n = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                        onBytes(written + n)
                    }
                    n
                }
                written += got
                if (!chunked || got < CHUNK) break
            }
        }
        if (written == 0L) throw IOException("Empty download")
    }

    private suspend fun fetchWithRetry(url: String, read: (java.io.InputStream) -> Long): Long {
        var last: Exception? = null
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                val req = Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT).build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                    return read(res.body!!.byteStream())
                }
            } catch (e: IOException) {
                last = e
                if (e.message?.startsWith("HTTP 4") == true) throw e // expired/forbidden: retrying won't help
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }
}
