package com.mudassir.ytdownloader.ui

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.mudassir.ytdownloader.MainActivity
import com.mudassir.ytdownloader.Queue
import com.mudassir.ytdownloader.R
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.Sync
import com.mudassir.ytdownloader.Updater

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        findPreference<Preference>("check_now")?.setOnPreferenceClickListener {
            (activity as? MainActivity)?.checkForUpdates(manual = true)
            true
        }
        val ctx = requireContext()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        findPreference<Preference>("version")?.summary =
            "${info.versionName} · build ${Updater.installedBuild(ctx)}"
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        val ctx = context ?: return
        when (key) {
            Settings.K_WIFI_ONLY -> { Queue.restart(ctx); Sync.schedule(ctx) }
            Settings.K_PARALLEL -> Queue.restart(ctx)
            Settings.K_AUTO_SYNC -> Sync.schedule(ctx)
        }
    }
}
