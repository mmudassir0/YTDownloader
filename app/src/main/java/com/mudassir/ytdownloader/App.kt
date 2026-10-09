package com.mudassir.ytdownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import org.schabi.newpipe.extractor.NewPipe

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(OkHttpDownloader.instance)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
    }
}
