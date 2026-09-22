package com.example.routeremu

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class RouterEmuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                QemuService.NOTIFICATION_CHANNEL_ID,
                getString(R.string.qemu_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while the emulated router firmware is running"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val PREFS_NAME = "router_emu_prefs"
    }
}
