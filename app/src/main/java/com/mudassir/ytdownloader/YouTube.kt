package com.mudassir.ytdownloader

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** One downloadable file option for a video. */
data class DownloadOption(
    val label: String,
    val url: String,
    val extension: String,
    val isAudio: Boolean,
    val sortKey: Int
)

data class VideoDetails(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val options: List<DownloadOption>
)

data class PlaylistDetails(
    val name: String,
    val videos: List<StreamInfoItem>
)

object YouTube {

    private val service get() = ServiceList.YouTube

    fun isPlaylist(url: String): Boolean =
        url.contains("list=") && !url.contains("watch?v=") ||
            url.contains("/playlist")

    /** Fetches a video and returns progressive (single-file) download options, best first. */
    fun getVideo(url: String): VideoDetails {
        val info = StreamInfo.getInfo(service, url)
        val options = mutableListOf<DownloadOption>()

        // Video + audio in one file. YouTube usually only offers these up to 360p/720p.
        info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .forEach { s ->
                val ext = s.format?.suffix ?: "mp4"
                options += DownloadOption(
                    label = "Video ${s.resolution} (${ext.uppercase()})",
                    url = s.content,
                    extension = ext,
                    isAudio = false,
                    sortKey = s.resolution.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
                )
            }

        // Audio only (good for music / lectures).
        info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .forEach { s ->
                val ext = s.format?.suffix ?: "m4a"
                options += DownloadOption(
                    label = "Audio ${s.averageBitrate} kbps (${ext.uppercase()})",
                    url = s.content,
                    extension = ext,
                    isAudio = true,
                    sortKey = s.averageBitrate
                )
            }

        val sorted = options
            .distinctBy { it.label }
            .sortedWith(compareBy<DownloadOption> { it.isAudio }.thenByDescending { it.sortKey })

        return VideoDetails(info.name, info.uploaderName ?: "", info.duration, sorted)
    }

    /** Fetches every video in a playlist (follows pagination). */
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

    /** Picks the best option for batch (playlist) downloads. */
    fun pickBest(options: List<DownloadOption>, audioOnly: Boolean): DownloadOption? =
        options.filter { it.isAudio == audioOnly }.maxByOrNull { it.sortKey }
}
