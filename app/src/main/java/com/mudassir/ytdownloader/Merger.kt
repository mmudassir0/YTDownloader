package com.mudassir.ytdownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Merges a video-only MP4 and an AAC (m4a) audio file into one MP4, without re-encoding. */
object Merger {

    fun merge(video: File, audio: File, output: File) {
        val vEx = MediaExtractor().apply { setDataSource(video.path) }
        val aEx = MediaExtractor().apply { setDataSource(audio.path) }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vIn = selectTrack(vEx, "video/")
            val aIn = selectTrack(aEx, "audio/")
            val vOut = muxer.addTrack(vEx.getTrackFormat(vIn))
            val aOut = muxer.addTrack(aEx.getTrackFormat(aIn))
            muxer.start()

            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false

            // Interleave samples by timestamp so the result streams/seeks well.
            while (!vDone || !aDone) {
                val useVideo = when {
                    vDone -> false
                    aDone -> true
                    else -> vEx.sampleTime <= aEx.sampleTime
                }
                val ex = if (useVideo) vEx else aEx
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) {
                    if (useVideo) vDone = true else aDone = true
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(if (useVideo) vOut else aOut, buffer, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            vEx.release()
            aEx.release()
        }
    }

    private fun selectTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) {
                ex.selectTrack(i)
                return i
            }
        }
        throw IllegalStateException("No $prefix track found")
    }
}
