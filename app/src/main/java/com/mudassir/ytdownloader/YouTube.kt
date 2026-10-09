package com.mudassir.ytdownloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit

enum class Kind { MERGE, SINGLE_VIDEO, AUDIO }

/** One downloadable choice. MERGE = separate HD video + audio tracks, merged on the phone. */
data class DownloadOption(
    val kind: Kind,
    val height: Int,
    val label: String,
    val videoUrl: String?,
    val audioUrl: String?,
    val extension: String,
    val bytes: Long // -1 if unknown
) {
    val displayLabel: String
        get() = if (bytes > 0) "$label · ${formatBytes(bytes)}" else label
}

data class VideoDetails(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val options: List<DownloadOption>
)

data class PlaylistDetails(val name: String, val videos: List<StreamInfoItem>)

object YouTube {

    private val service get() = ServiceList.YouTube
    private val headClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    fun isPlaylist(url: String): Boolean =
        (url.contains("list=") && !url.contains("watch?v=") && !url.contains("youtu.be/")) ||
            url.contains("/playlist")

    fun getVideo(url: String): VideoDetails {
        val info = StreamInfo.getInfo(service, url)

        // Best AAC (m4a) audio track — merges cleanly into MP4.
        val audios = info.audioStreams
            .filter { it.usable() && it.format == MediaFormat.M4A }
            .sortedByDescending { it.averageBitrate }
        val bestAudio = audios.firstOrNull()
        val audioBytes = bestAudio?.let { sizeOf(it) } ?: -1L

        val options = mutableListOf<DownloadOption>()

        // HD: video-only H.264 MP4 tracks (up to 1080p), one per resolution, best bitrate.
        if (bestAudio != null) {
            info.videoOnlyStreams
                .filter { it.usable() && it.format == MediaFormat.MPEG_4 && (it.codec ?: "avc1").startsWith("avc1") }
                .groupBy { it.height }
                .mapNotNull { (_, list) -> list.maxByOrNull { it.bitrate } }
                .sortedByDescending { it.height }
                .forEach { v ->
                    val vb = sizeOf(v)
                    options += DownloadOption(
                        kind = Kind.MERGE,
                        height = v.height,
                        label = "${v.resolution} · MP4",
                        videoUrl = v.content,
                        audioUrl = bestAudio.content,
                        extension = "mp4",
                        bytes = if (vb > 0 && audioBytes > 0) vb + audioBytes else -1
                    )
                }
        }

        // Fallback: single-file video+audio (usually 360p only).
        info.videoStreams
            .filter { it.usable() }
            .filter { v -> options.none { it.height == v.height } }
            .forEach { v ->
                options += DownloadOption(
                    kind = Kind.SINGLE_VIDEO,
                    height = v.height,
                    label = "${v.resolution} · ${(v.format?.suffix ?: "mp4").uppercase()}",
                    videoUrl = v.content,
                    audioUrl = null,
                    extension = v.format?.suffix ?: "mp4",
                    bytes = sizeOf(v)
                )
            }

        options.sortByDescending { it.height }

        // Audio only.
        audios.distinctBy { it.averageBitrate }.take(2).forEach { a ->
            options += DownloadOption(
                kind = Kind.AUDIO,
                height = 0,
                label = "Audio only ${a.averageBitrate} kbps · M4A",
                videoUrl = null,
                audioUrl = a.content,
                extension = "m4a",
                bytes = sizeOf(a)
            )
        }

        fillMissingSizes(options)
        return VideoDetails(info.name, info.uploaderName ?: "", info.duration, options)
    }

    /** Best option at or below [maxHeight]; maxHeight == 0 means audio only. */
    fun pickBest(options: List<DownloadOption>, maxHeight: Int): DownloadOption? =
        if (maxHeight == 0) options.firstOrNull { it.kind == Kind.AUDIO }
        else options.filter { it.kind != Kind.AUDIO && it.height <= maxHeight }.maxByOrNull { it.height }
            ?: options.filter { it.kind != Kind.AUDIO }.minByOrNull { it.height }

    fun getPlaylist(url: String, onProgress: (Int) -> Unit = {}): PlaylistDetails {
        val info = PlaylistInfo.getInfo(service, url)
        val items = info.relatedItems.toMutableList()
        onProgress(items.size)
        var next: Page? = info.nextPage
        while (Page.isValid(next)) {
            val more = PlaylistInfo.getMoreItems(service, url, next)
            items += more.items
            onProgress(items.size)
            next = more.nextPage
        }
        return PlaylistDetails(info.name, items)
    }

    // --- helpers ---

    private fun Stream.usable() = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && isUrl

    /** Size from YouTube's metadata or the "clen" URL parameter, without any network call. */
    private fun sizeOf(s: Stream): Long {
        val fromItag = when (s) {
            is VideoStream -> s.itagItem?.contentLength ?: -1L
            is AudioStream -> s.itagItem?.contentLength ?: -1L
            else -> -1L
        }
        if (fromItag > 0) return fromItag
        return Regex("[?&]clen=(\\d+)").find(s.content)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    }

    /** For anything still unknown, ask the server (HEAD) in parallel. */
    private fun fillMissingSizes(options: MutableList<DownloadOption>) {
        if (options.none { it.bytes <= 0 }) return
        runBlocking(Dispatchers.IO) {
            val filled = options.map { o ->
                async {
                    if (o.bytes > 0) o
                    else {
                        val v = o.videoUrl?.let { head(it) } ?: 0L
                        val a = o.audioUrl?.let { head(it) } ?: 0L
                        val ok = (o.videoUrl == null || v > 0) && (o.audioUrl == null || a > 0)
                        o.copy(bytes = if (ok) v + a else -1)
                    }
                }
            }.awaitAll()
            options.clear()
            options.addAll(filled)
        }
    }

    private fun head(url: String): Long = try {
        headClient.newCall(
            Request.Builder().url(url).head().header("User-Agent", OkHttpDownloader.USER_AGENT).build()
        ).execute().use { it.header("Content-Length")?.toLongOrNull() ?: -1L }
    } catch (e: Exception) {
        -1L
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
