package app.mangalens

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class MangaLensApp : Application() {

    companion object {
        const val CHANNEL_ID = "mangalens"
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen translation", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
