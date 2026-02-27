package net.aurorasentient.autotechgateway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AutotechApp : Application() {

    companion object {
        const val CHANNEL_ID = "autotech_gateway_service"
        const val CHANNEL_NAME = "Gateway Service"
        const val VERSION = "1.0.0"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Autotech Gateway connection status"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
