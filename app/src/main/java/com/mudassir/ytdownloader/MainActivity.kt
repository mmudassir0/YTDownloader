package com.mudassir.ytdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.mudassir.ytdownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var videoUrl: String? = null
    private var video: VideoDetails? = null
    private var playlist: PlaylistDetails? = null
    private var playlistUrl: String? = null
    private var sizeJob: Job? = null

    /** Playlist quality choices: label to max height (0 = audio only). */
    private val playlistQualities = listOf(
        "1080p" to 1080, "720p" to 720, "480p" to 480, "360p" to 360, "Audio only" to 0
    )

    /** Per-video resolved options, filled while calculating the playlist size. */
    private val resolved = mutableMapOf<String, List<DownloadOption>>()

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        requestNeededPermissions()

        b.pasteButton.setOnClickListener { pasteFromClipboard() }
        b.fetchButton.setOnClickListener { fetch() }
        b.downloadButton.setOnClickListener { downloadSelectedVideo() }
        b.downloadAllButton.setOnClickListener { downloadPlaylist() }

        b.formatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateVideoSize()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        b.qualitySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, playlistQualities.map { it.first }
        )
        b.qualitySpinner.setSelection(1)
        b.qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updatePlaylistSize()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        b.urlInput.setText(url)
        fetch()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) toast("Clipboard is empty") else b.urlInput.setText(text.trim())
    }

    private fun fetch() {
        val url = b.urlInput.text.toString().trim()
        if (!url.startsWith("http")) {
            toast("Paste a YouTube video or playlist link")
            return
        }
        resetResults()
        setLoading(true, "Fetching…")

        lifecycleScope.launch {
            try {
                if (YouTube.isPlaylist(url)) {
                    val pl = withContext(Dispatchers.IO) {
                        YouTube.getPlaylist(url) { n ->
                            runOnUiThread { b.statusText.text = "Loading playlist… $n videos" }
                        }
                    }
                    playlist = pl
                    playlistUrl = url
                    showPlaylist(pl)
                } else {
                    val v = withContext(Dispatchers.IO) { YouTube.getVideo(url) }
                    video = v
                    videoUrl = url
                    showVideo(v)
                }
            } catch (e: Exception) {
                b.statusText.text = "Error: ${e.message ?: e.javaClass.simpleName}"
                b.statusText.isVisible = true
            } finally {
                setLoading(false)
            }
        }
    }

    // ---------- single video ----------

    private fun showVideo(v: VideoDetails) {
        b.titleText.text = v.title
        b.subtitleText.text = "${v.uploader} • ${formatDuration(v.durationSec)}"
        b.videoCard.isVisible = true
        if (v.options.isEmpty()) {
            b.statusText.text = "No downloadable formats found for this video."
            b.statusText.isVisible = true
            return
        }
        b.formatSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, v.options.map { it.displayLabel }
        )
        // Default to 720p if available, else the best one.
        val def = v.options.indexOfFirst { it.kind != Kind.AUDIO && it.height <= 720 }.takeIf { it >= 0 } ?: 0
        b.formatSpinner.setSelection(def)
        b.formatSpinner.isVisible = true
        b.downloadButton.isVisible = true
        b.sizeText.isVisible = true
        updateVideoSize()
    }

    private fun updateVideoSize() {
        val opt = video?.options?.getOrNull(b.formatSpinner.selectedItemPosition) ?: return
        b.sizeText.text = sizeLine(opt.bytes, exact = true)
    }

    private fun downloadSelectedVideo() {
        val v = video ?: return
        val url = videoUrl ?: return
        val opt = v.options.getOrNull(b.formatSpinner.selectedItemPosition) ?: return
        val maxHeight = if (opt.kind == Kind.AUDIO) 0 else opt.height
        DownloadWorker.enqueue(this, url, v.title, maxHeight)
        toast("Download started. Progress is in your notifications")
    }

    // ---------- playlist ----------

    private fun showPlaylist(pl: PlaylistDetails) {
        b.titleText.text = pl.name
        val totalSec = pl.videos.sumOf { maxOf(it.duration, 0L) }
        b.subtitleText.text = "${pl.videos.size} videos • ${formatDuration(totalSec)} total"
        b.videoCard.isVisible = true
        b.qualityRow.isVisible = true
        b.downloadAllButton.isVisible = true
        b.sizeText.isVisible = true
        calculatePlaylistSize(pl)
    }

    /** Looks up every video once (4 at a time) so we can show the exact total size. */
    private fun calculatePlaylistSize(pl: PlaylistDetails) {
        sizeJob?.cancel()
        resolved.clear()
        val done = AtomicInteger(0)
        b.sizeText.text = "Calculating size… 0 / ${pl.videos.size}"
        sizeJob = lifecycleScope.launch {
            val gate = Semaphore(4)
            val jobs = pl.videos.map { item ->
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        runCatching { YouTube.getVideo(item.url).options }
                            .onSuccess { synchronized(resolved) { resolved[item.url] = it } }
                        val n = done.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            if (n < pl.videos.size) b.sizeText.text = "Calculating size… $n / ${pl.videos.size}"
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            updatePlaylistSize()
        }
    }

    private fun updatePlaylistSize() {
        val pl = playlist ?: return
        if (sizeJob?.isActive == true) return
        val maxHeight = playlistQualities[b.qualitySpinner.selectedItemPosition].second
        var total = 0L
        var unknown = 0
        pl.videos.forEach { item ->
            val opt = resolved[item.url]?.let { YouTube.pickBest(it, maxHeight) }
            if (opt == null || opt.bytes <= 0) unknown++ else total += opt.bytes
        }
        val extra = if (unknown > 0) "\n$unknown video(s) unavailable or size unknown" else ""
        b.sizeText.text = sizeLine(total, exact = unknown == 0) + extra
    }

    private fun downloadPlaylist() {
        val pl = playlist ?: return
        val maxHeight = playlistQualities[b.qualitySpinner.selectedItemPosition].second
        pl.videos.forEachIndexed { i, item ->
            DownloadWorker.enqueue(
                this, item.url, item.name, maxHeight,
                prefix = "%03d".format(i + 1), folder = pl.name
            )
        }
        b.statusText.text = "Queued ${pl.videos.size} videos. They download one by one, " +
            "even if you close the app. Saving to Downloads/${Storage.FOLDER}/${Storage.sanitize(pl.name)}"
        b.statusText.isVisible = true
    }

    // ---------- helpers ----------

    private fun sizeLine(bytes: Long, exact: Boolean): String {
        val free = Storage.freeBytes()
        val size = if (bytes > 0) (if (exact) "" else "≈ ") + formatBytes(bytes) else "unknown"
        val warn = if (bytes > 0 && free in 0 until bytes * 2) "  ⚠ not enough space" else ""
        return "Data needed: $size   •   Free on phone: ${formatBytes(free)}$warn"
    }

    private fun resetResults() {
        sizeJob?.cancel()
        video = null; videoUrl = null
        playlist = null; playlistUrl = null
        b.videoCard.isVisible = false
        b.formatSpinner.isVisible = false
        b.downloadButton.isVisible = false
        b.qualityRow.isVisible = false
        b.downloadAllButton.isVisible = false
        b.sizeText.isVisible = false
        b.statusText.isVisible = false
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        b.progress.isVisible = loading
        b.fetchButton.isEnabled = !loading
        if (loading) {
            b.statusText.text = message
            b.statusText.isVisible = true
        } else if (b.statusText.text.startsWith("Fetching") || b.statusText.text.startsWith("Loading")) {
            b.statusText.visibility = View.GONE
        }
    }

    private fun requestNeededPermissions() {
        val perm = when {
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.POST_NOTIFICATIONS
            Build.VERSION.SDK_INT <= 28 -> Manifest.permission.WRITE_EXTERNAL_STORAGE
            else -> return
        }
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            askPermission.launch(perm)
        }
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
