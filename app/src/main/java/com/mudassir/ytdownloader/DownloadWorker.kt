package com.mudassir.ytdownloader

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs one download in the background (keeps going if you leave the app).
 * It re-reads the video page itself, because YouTube stream links expire after a few hours.
 * All downloads go through one queue, so a playlist downloads one video at a time.
 *
 * Important: a worker in the queue must never end in Result.failure() and must never be
 * cancelled on its own, because WorkManager then cancels everything queued after it.
 * So a failed or skipped video finishes as "success" (with an error in its output data)
 * and the queue moves on to the next one.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val notifId = id.hashCode()

    private class Skipped : CancellationException("Skipped")

    override suspend fun doWork(): Result = coroutineScope {
        // Run the download as a child job so "Skip" can cancel just this video.
        val job = async(Dispatchers.IO) { download() }
        running[id] = job
        try {
            job.await()
        } catch (e: CancellationException) {
            if (isStopped) throw e // "Stop all" or system stop: let WorkManager handle it
            done(inputData.getString(K_TITLE) ?: "Video", "Skipped")
            Result.success(workDataOf("error" to "Skipped"))
        } finally {
            running.remove(id)
        }
    }

    private suspend fun download(): Result = withContext(Dispatchers.IO) {
        val pageUrl = inputData.getString(K_URL) ?: return@withContext fail("Video", "Missing link")
        val maxHeight = inputData.getInt(K_HEIGHT, 720)
        val prefix = inputData.getString(K_PREFIX)
        val subFolder = inputData.getString(K_FOLDER)
        var title = inputData.getString(K_TITLE) ?: "Video"

        val work = File(applicationContext.cacheDir, "dl-$id").apply { mkdirs() }
        try {
            setForeground(foreground(title, "Starting…", 0, 0))

            val details = YouTube.getVideo(pageUrl)
            title = details.title
            val opt = YouTube.pickBest(details.options, maxHeight)
                ?: return@withContext fail(title, "No downloadable format")

            val baseName = Storage.sanitize(if (prefix != null) "$prefix - $title" else title)
            val fileName = "$baseName.${opt.extension}"
            val total = opt.bytes
            val qual = opt.label

            val free = Storage.freeBytes()
            if (total > 0 && free in 0 until total * 2 + 50_000_000) {
                return@withContext fail(title, "Not enough space: needs ${formatBytes(total * 2)}, free ${formatBytes(free)}")
            }

            var lastPct = -1
            fun progress(done: Long, stage: String) {
                val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                if (pct != lastPct) {
                    lastPct = pct
                    val text = if (total > 0) "$stage ${formatBytes(done)} / ${formatBytes(total)}" else stage
                    nm.notify(notifId, notification(title, text, pct, if (total > 0) 100 else 0).build())
                    setProgressAsync(workDataOf("pct" to pct))
                }
            }

            val result: File
            val mime: String
            when (opt.kind) {
                Kind.MERGE -> {
                    val v = File(work, "v.mp4")
                    val a = File(work, "a.m4a")
                    ChunkDownloader.download(opt.videoUrl!!, v) { progress(it, "$qual ·") }
                    val vLen = v.length()
                    ChunkDownloader.download(opt.audioUrl!!, a) { progress(vLen + it, "$qual ·") }
                    nm.notify(notifId, notification(title, "Merging video + audio…", 0, 0).build())
                    result = File(work, "out.mp4")
                    Merger.merge(v, a, result)
                    v.delete(); a.delete()
                    mime = "video/mp4"
                }
                Kind.SINGLE_VIDEO -> {
                    result = File(work, "out.${opt.extension}")
                    ChunkDownloader.download(opt.videoUrl!!, result) { progress(it, "$qual ·") }
                    mime = "video/${opt.extension}"
                }
                Kind.AUDIO -> {
                    result = File(work, "out.m4a")
                    ChunkDownloader.download(opt.audioUrl!!, result) { progress(it, "$qual ·") }
                    mime = "audio/mp4"
                }
            }

            nm.notify(notifId, notification(title, "Saving…", 0, 0).build())
            val folder = Storage.saveToDownloads(applicationContext, result, fileName, mime, subFolder)
            done(title, "Saved · ${formatBytes(result.length())} · $folder")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else fail(title, e.message ?: e.javaClass.simpleName)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Reports the failure, but returns success so the rest of the queue keeps going. */
    private fun fail(title: String, why: String): Result {
        done(title, "Failed: $why")
        return Result.success(workDataOf("error" to why))
    }

    private fun done(title: String, text: String) {
        nm.cancel(notifId)
        nm.notify(
            notifId + 1,
            NotificationCompat.Builder(applicationContext, App.CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notification(title: String, text: String, pct: Int, max: Int) =
        NotificationCompat.Builder(applicationContext, App.CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, pct, max == 0)
            .setContentIntent(openApp())
            .addAction(android.R.drawable.ic_media_next, "Skip", controlIntent(ACTION_SKIP))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop all", controlIntent(ACTION_STOP_ALL))

    private fun controlIntent(action: String) = PendingIntent.getBroadcast(
        applicationContext,
        (action + id).hashCode(),
        Intent(applicationContext, Control::class.java).setAction(action).putExtra(K_ID, id.toString()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Handles the notification buttons. */
    class Control : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SKIP -> intent.getStringExtra(K_ID)
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?.let { running[it]?.cancel(Skipped()) }
                ACTION_STOP_ALL -> WorkManager.getInstance(ctx).cancelUniqueWork(QUEUE)
            }
        }
    }

    private fun foreground(title: String, text: String, pct: Int, max: Int): ForegroundInfo {
        val n = notification(title, text, pct, max).build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notifId, n)
    }

    private fun openApp() = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val K_URL = "url"
        private const val K_HEIGHT = "height"
        private const val K_TITLE = "title"
        private const val K_PREFIX = "prefix"
        private const val K_FOLDER = "folder"
        private const val K_ID = "id"
        private const val ACTION_SKIP = "com.mudassir.ytdownloader.SKIP"
        private const val ACTION_STOP_ALL = "com.mudassir.ytdownloader.STOP_ALL"
        const val QUEUE = "yt-queue"

        /** Download jobs currently running in this process, by WorkManager id. */
        private val running = ConcurrentHashMap<UUID, Deferred<Result>>()

        /** maxHeight: 0 = audio only, otherwise the highest resolution to allow (e.g. 1080). */
        fun enqueue(ctx: Context, url: String, title: String, maxHeight: Int, prefix: String? = null, folder: String? = null) {
            val data = Data.Builder()
                .putString(K_URL, url)
                .putString(K_TITLE, title)
                .putInt(K_HEIGHT, maxHeight)
                .putString(K_PREFIX, prefix)
                .putString(K_FOLDER, folder)
                .build()
            val req = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data).addTag(QUEUE).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
