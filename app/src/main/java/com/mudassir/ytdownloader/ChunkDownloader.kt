package com.mudassir.ytdownloader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** HTTP error that retrying the same URL won't fix (expired or forbidden link). */
class HttpClientError(val code: Int) : IOException("HTTP $code")

/**
 * Downloads YouTube media in 10 MB pieces (the "range" URL parameter), the same way
 * the official apps do. Plain full-file requests get throttled or rejected for HD tracks.
 *
 * Each piece is written at its exact offset, and a failed attempt is rolled back before
 * retrying, so a dropped connection can never leave duplicated bytes in the file.
 */
object ChunkDownloader {

    private const val CHUNK = 10L * 1024 * 1024
    private const val ATTEMPTS = 3
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** @param onBytes called with bytes written so far for this file. */
    suspend fun download(url: String, dest: File, onBytes: (Long) -> Unit) {
        val chunked = url.contains("googlevideo.com")
        val ctx = currentCoroutineContext()
        var written = 0L
        RandomAccessFile(dest, "rw").use { out ->
            out.setLength(0)
            val buf = ByteArray(64 * 1024)
            while (true) {
                ctx.ensureActive()
                val reqUrl = if (chunked) "$url&range=$written-${written + CHUNK - 1}" else url
                val got = fetchWithRetry(
                    url = reqUrl,
                    // Start every attempt from a clean end-of-file at this chunk's offset.
                    beforeAttempt = { out.setLength(written); out.seek(written) },
                    read = { input ->
                        var n = 0L
                        while (true) {
                            ctx.ensureActive() // lets Skip / Stop take effect mid-chunk
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            n += r
                            onBytes(written + n)
                        }
                        n
                    }
                )
                written += got
                if (!chunked || got < CHUNK) break
            }
        }
        if (written == 0L) throw IOException("Empty download")
    }

    private suspend fun fetchWithRetry(
        url: String,
        beforeAttempt: () -> Unit,
        read: (InputStream) -> Long
    ): Long {
        var last: IOException? = null
        for (attempt in 0 until ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                beforeAttempt()
                val req = Request.Builder().url(url).header("User-Agent", OkHttpDownloader.USER_AGENT).build()
                return client.newCall(req).execute().use { res ->
                    if (res.code in 400..499) throw HttpClientError(res.code)
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                    read(res.body!!.byteStream())
                }
            } catch (e: HttpClientError) {
                throw e // expired/forbidden link: the worker re-extracts a fresh one on retry
            } catch (e: IOException) {
                last = e
                if (attempt < ATTEMPTS - 1) delay(1500L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }
}
