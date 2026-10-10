package com.mudassir.ytdownloader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.load
import com.google.common.util.concurrent.ListenableFuture
import com.mudassir.ytdownloader.databinding.ActivityPlayerBinding

/** Full-screen player for downloaded files; continues where you left off. */
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var future: ListenableFuture<MediaController>? = null

    private val uri get() = intent.getStringExtra(EXTRA_URI)!!
    private val isAudio get() = intent.getStringExtra(EXTRA_MIME)?.startsWith("audio") == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        b.artwork.isVisible = isAudio
        if (isAudio) {
            b.artwork.load(intent.getStringExtra(EXTRA_THUMB))
            b.playerView.controllerShowTimeoutMs = 0 // keep controls visible for music
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val controller = runCatching { f.get() }.getOrNull() ?: return@addListener
            b.playerView.player = controller
            if (controller.currentMediaItem?.mediaId != uri) {
                val item = MediaItem.Builder()
                    .setMediaId(uri)
                    .setUri(Uri.parse(uri))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(intent.getStringExtra(EXTRA_TITLE))
                            .setArtworkUri(intent.getStringExtra(EXTRA_THUMB)?.let(Uri::parse))
                            .build()
                    )
                    .build()
                controller.setMediaItem(item, Settings(this).position(uri))
                controller.prepare()
            }
            controller.play()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        // Video pauses when you leave; audio keeps playing in the background.
        future?.let { f ->
            if (f.isDone && !isAudio) runCatching { f.get().pause() }
            b.playerView.player = null
            MediaController.releaseFuture(f)
        }
        future = null
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_THUMB = "thumb"

        fun intent(ctx: Context, item: DownloadItem) = Intent(ctx, PlayerActivity::class.java)
            .putExtra(EXTRA_URI, item.fileUri)
            .putExtra(EXTRA_TITLE, item.title)
            .putExtra(EXTRA_MIME, item.mime)
            .putExtra(EXTRA_THUMB, item.thumbnail)
    }
}
