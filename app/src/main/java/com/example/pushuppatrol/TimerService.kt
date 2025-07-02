package com.example.pushuppatrol // Replace with your actual package name

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class TimerService : Service() {

    private lateinit var timeBankManager: TimeBankManager
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob) // Use IO for SharedPreferences

    private var currentMonitoredApp: String? = null

    companion object {
        private const val TAG = "TimerService"
        const val ACTION_START_TIMER = "com.example.pushuppatrol.ACTION_START_TIMER"
        const val ACTION_STOP_TIMER = "com.example.pushuppatrol.ACTION_STOP_TIMER"
        const val EXTRA_APP_PACKAGE = "com.example.pushuppatrol.EXTRA_APP_PACKAGE"

        private const val NOTIFICATION_CHANNEL_ID = "TimerServiceChannel"
        private const val NOTIFICATION_ID = 123 // Must be unique within your app

        // To communicate time expiration (Micro-Goal 2.3)
        const val BROADCAST_TIME_EXPIRED = "com.example.pushuppatrol.TIME_EXPIRED"
    }

    override fun onCreate() {
        super.onCreate()
        timeBankManager = TimeBankManager(applicationContext)
        createNotificationChannel()
        Log.d(TAG, "TimerService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_TIMER -> {
                val appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE)
                if (appPackage != null) {
                    currentMonitoredApp = appPackage
                    startForeground(NOTIFICATION_ID, createNotification("Monitoring $appPackage"))
                    startTimerRoutine()
                    Log.i(TAG, "Timer started for $appPackage")
                } else {
                    Log.w(TAG, "ACTION_START_TIMER but no app package provided.")
                    stopSelfIfNotMonitoring()
                }
            }
            ACTION_STOP_TIMER -> {
                Log.i(TAG, "Timer stop action received.")
                stopTimerRoutine() // Stops coroutine
                stopForeground(true) // Remove notification
                stopSelf() // Stop the service itself
            }
            else -> {
                Log.w(TAG, "Unknown action or null intent, stopping service to be safe.")
                stopSelfIfNotMonitoring()
            }
        }
        return START_STICKY // If killed, try to restart, but intent will be null
    }

    private fun startTimerRoutine() {
        serviceScope.launch {
            Log.d(TAG, "Coroutine timer routine started.")
            try {
                while (isActive) { // Loop while coroutine is active
                    delay(1000) // Wait for 1 second

                    val remainingTime = timeBankManager.getTimeSeconds()
                    if (remainingTime > 0) {
                        val timeUsedSuccessfully = timeBankManager.useTime(1)
                        if (timeUsedSuccessfully) {
                            val newRemainingTime = timeBankManager.getTimeSeconds()
                            Log.d(TAG, "Time tick. Remaining: $newRemainingTime s")
                            updateNotification("Time left: ${formatTime(newRemainingTime)}")
                        } else {
                            // This case should ideally not happen if remainingTime > 0 check passed,
                            // but good for safety.
                            Log.w(TAG, "Failed to use 1 second, though time was > 0.")
                            // Potentially stop if time cannot be reliably decremented.
                        }
                    } else {
                        Log.i(TAG, "Time is up in coroutine. Sending broadcast.")
                        // Time has run out
                        sendBroadcast(Intent(BROADCAST_TIME_EXPIRED).apply {
                            putExtra(EXTRA_APP_PACKAGE, currentMonitoredApp)
                        })
                        stopTimerRoutine() // Stop this coroutine
                        stopSelf()      // Stop the service itself
                        break // Exit loop
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Timer coroutine cancelled.")
                // This is expected when stopTimerRoutine is called
            } catch (e: Exception) {
                Log.e(TAG, "Error in timer coroutine", e)
                // Optionally stop service on unexpected error
            } finally {
                Log.d(TAG, "Timer coroutine finishing.")
            }
        }
    }

    private fun stopTimerRoutine() {
        if (serviceJob.isActive) {
            Log.d(TAG, "Stopping timer coroutine job.")
            serviceJob.cancelChildren() // Cancel any children coroutines
            serviceJob.cancel()         // Cancel the job itself
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Push-up Patrol Timer",
                NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound/vibration unless critical
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        // Intent to open MainActivity when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Push-up Patrol Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your app's icon
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true) // Don't alert every time notification is updated
            .setOngoing(true)       // Makes the notification non-dismissible
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText) // Rebuild with new text
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun formatTime(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun stopSelfIfNotMonitoring() {
        // If the service was started without a valid monitoring task, stop it.
        if (!serviceJob.isActive || currentMonitoredApp == null) {
            Log.w(TAG, "Stopping service as it's not actively monitoring or has no target.")
            stopForeground(true)
            stopSelf()
        }
    }


    override fun onBind(intent: Intent): IBinder? {
        // We don't provide binding, so return null
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "TimerService destroyed.")
        stopTimerRoutine() // Ensure coroutine is stopped
        super.onDestroy()
    }
}