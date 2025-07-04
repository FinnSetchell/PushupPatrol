package com.example.pushuppatrol // Ensure this matches your package name

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class MainApplication : Application() {

    companion object {
        // Define the channel ID here, so other components (like TimerService) can access it.
        const val TIMER_SERVICE_CHANNEL_ID = "TimerServiceChannel"
        // You can add other channel IDs here if needed for different types of notifications later
    }

    override fun onCreate() {
        super.onCreate()
        createAppNotificationChannels()
    }

    private fun createAppNotificationChannels() {
        // Notification channels are only available on API 26+

        // Channel for the Timer Service
        val timerServiceChannel = NotificationChannel(
            TIMER_SERVICE_CHANNEL_ID,
            "App Usage Timer", // User-visible name of the channel
            NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound/peeking for ongoing status
        ).apply {
            description = "Displays the remaining time for apps being actively monitored."
            setSound(null, null) // No sound for updates to this ongoing notification
            enableVibration(false) // No vibration for updates
            // You could set other properties like setShowBadge(false) if desired
        }

        // Get the NotificationManager system service
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the channel
        manager.createNotificationChannel(timerServiceChannel)

        // If you had other channels, you would create them here too
        // Example:
        // val alertsChannel = NotificationChannel(...)
        // manager.createNotificationChannel(alertsChannel)
    }
}