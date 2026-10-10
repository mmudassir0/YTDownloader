package com.mudassir.ytdownloader

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** App settings, backed by the same SharedPreferences the Settings screen edits. */
class Settings(ctx: Context) {
    private val p: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)

    /** Only download on Wi-Fi / unmetered networks. */
    val wifiOnly get() = p.getBoolean(K_WIFI_ONLY, false)

    /** How many videos download at the same time (1–3). */
    val parallel get() = (p.getString(K_PARALLEL, "1")?.toIntOrNull() ?: 1).coerceIn(1, 3)

    /** Default max height for new downloads; 0 = audio only. */
    val defaultQuality get() = p.getString(K_QUALITY, "720")?.toIntOrNull() ?: 720

    /** Subtitle language code to save with videos ("" = off), e.g. "en". */
    val subtitleLang get() = p.getString(K_SUBS, "")?.trim().orEmpty()

    /** Remove sponsor / self-promotion segments using SponsorBlock. */
    val sponsorBlock get() = p.getBoolean(K_SPONSORBLOCK, false)

    /** Allow 1440p / 4K (VP9 + Opus in WebM). Off = MP4 only, max 1080p. */
    val allowWebm get() = p.getBoolean(K_WEBM, true)

    /** Check synced playlists for new videos every night. */
    val autoSync get() = p.getBoolean(K_AUTO_SYNC, false)

    /** Check GitHub for a newer version on launch. */
    val autoUpdateCheck get() = p.getBoolean(K_UPDATE_CHECK, true)

    var paused: Boolean
        get() = p.getBoolean(K_PAUSED, false)
        set(v) = p.edit().putBoolean(K_PAUSED, v).apply()

    var lastUpdateCheck: Long
        get() = p.getLong(K_LAST_UPDATE_CHECK, 0)
        set(v) = p.edit().putLong(K_LAST_UPDATE_CHECK, v).apply()

    /** Saved playback position (ms) for a file, used by the player to continue watching. */
    fun position(uri: String): Long = p.getLong("pos:$uri", 0)
    fun setPosition(uri: String, ms: Long) = p.edit().putLong("pos:$uri", ms).apply()

    companion object {
        const val K_WIFI_ONLY = "wifi_only"
        const val K_PARALLEL = "parallel"
        const val K_QUALITY = "default_quality"
        const val K_SUBS = "subtitle_lang"
        const val K_SPONSORBLOCK = "sponsorblock"
        const val K_WEBM = "allow_webm"
        const val K_AUTO_SYNC = "auto_sync"
        const val K_UPDATE_CHECK = "update_check"
        private const val K_PAUSED = "paused"
        private const val K_LAST_UPDATE_CHECK = "last_update_check"

        /** Quality choices shown everywhere: label to max height (0 = audio only). */
        val QUALITIES = listOf(
            "2160p (4K)" to 2160, "1440p" to 1440, "1080p" to 1080, "720p" to 720,
            "480p" to 480, "360p" to 360, "Audio only" to 0
        )
    }
}
