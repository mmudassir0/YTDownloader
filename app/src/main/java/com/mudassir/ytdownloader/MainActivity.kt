package com.mudassir.ytdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.mudassir.ytdownloader.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var video: VideoDetails? = null
    private var playlist: PlaylistDetails? = null

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        NewPipe.init(OkHttpDownloader.instance)
        requestNeededPermissions()

        b.pasteButton.setOnClickListener { pasteFromClipboard() }
        b.fetchButton.setOnClickListener { fetch() }
        b.downloadButton.setOnClickListener { downloadSelectedVideo() }
        b.downloadAllButton.setOnClickListener { downloadPlaylist() }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Opened via "Share → YTDownloader" from the YouTube app. */
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
                        YouTube.getPlaylist(url) { count ->
                            runOnUiThread { b.statusText.text = "Loading playlist… $count videos" }
                        }
                    }
                    playlist = pl
                    showPlaylist(pl)
                } else {
                    val v = withContext(Dispatchers.IO) { YouTube.getVideo(url) }
                    video = v
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
            this, android.R.layout.simple_spinner_dropdown_item, v.options.map { it.label }
        )
        b.formatSpinner.isVisible = true
        b.downloadButton.isVisible = true
    }

    private fun showPlaylist(pl: PlaylistDetails) {
        b.titleText.text = pl.name
        b.subtitleText.text = "${pl.videos.size} videos"
        b.videoCard.isVisible = true
        b.playlistOptions.isVisible = true
        b.downloadAllButton.isVisible = true
    }

    private fun downloadSelectedVideo() {
        val v = video ?: return
        val option = v.options.getOrNull(b.formatSpinner.selectedItemPosition) ?: return
        FileSaver.enqueue(this, option, v.title)
        toast("Downloading to Downloads/${FileSaver.FOLDER}")
    }

    private fun downloadPlaylist() {
        val pl = playlist ?: return
        val audioOnly = b.audioOnlyRadio.isChecked
        setLoading(true, "Queuing 0 / ${pl.videos.size}…")
        b.downloadAllButton.isEnabled = false

        lifecycleScope.launch {
            var queued = 0
            var failed = 0
            pl.videos.forEachIndexed { i, item ->
                try {
                    val details = withContext(Dispatchers.IO) { YouTube.getVideo(item.url) }
                    val best = YouTube.pickBest(details.options, audioOnly)
                    if (best != null) {
                        val numbered = "%03d - %s".format(i + 1, details.title)
                        FileSaver.enqueue(this@MainActivity, best, numbered, pl.name)
                        queued++
                    } else failed++
                } catch (e: Exception) {
                    failed++
                }
                b.statusText.text = "Queuing ${i + 1} / ${pl.videos.size}…"
            }
            setLoading(false)
            b.downloadAllButton.isEnabled = true
            b.statusText.text = "Queued $queued downloads" +
                (if (failed > 0) ", $failed skipped (private/unavailable)" else "") +
                ". Saving to Downloads/${FileSaver.FOLDER}/${pl.name}"
            b.statusText.isVisible = true
        }
    }

    private fun resetResults() {
        video = null
        playlist = null
        b.videoCard.isVisible = false
        b.formatSpinner.isVisible = false
        b.downloadButton.isVisible = false
        b.playlistOptions.isVisible = false
        b.downloadAllButton.isVisible = false
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
