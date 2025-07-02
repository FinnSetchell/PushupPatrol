package com.example.pushuppatrol // Replace with your actual package name

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class TimerService : Service() {

    private lateinit var timeBankManager: TimeBankManager
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var currentMonitoredApp: String? = null

    companion object {
        private const val TAG = "TimerService"
        const val ACTION_START_TIMER = "com.example.pushuppatrol.ACTION_START_TIMER"
        const val ACTION_STOP_TIMER = "com.example.pushuppatrol.ACTION_STOP_TIMER"
        const val EXTRA_APP_PACKAGE = "com.example.pushuppatrol.EXTRA_APP_PACKAGE"

        private const val NOTIFICATION_CHANNEL_ID = "TimerServiceChannel"
        private const val NOTIFICATION_ID = 123 // Must be unique within your app

        // To communicate time expiration (Micro-Goal 2.3)
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
        Log.d(TAG, "onStartCommand received: ${intent?.action}, Job active: ${serviceJob?.isActive}, Current app: $currentMonitoredApp")
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Timer Service Active")
            .setContentText("Your timer is running.")
            .setSmallIcon(R.drawable.ic_tiny_pushup_patrol_logo)
            .build()

        Log.d(TAG, "Calling startForeground")
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Called startForeground. Notification ID: $NOTIFICATION_ID")

        when (intent?.action) {
            ACTION_START_TIMER -> {
                val appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE)
                if (appPackage != null) {
                    Log.i(TAG, "onStartCommand: ACTION_START_TIMER for $appPackage. Current monitored: $currentMonitoredApp. Job active: ${serviceJob?.isActive}")

                    if (appPackage == currentMonitoredApp && serviceJob?.isActive == true) {
                        Log.d(TAG, "Already monitoring $appPackage and timer routine is active. Ensuring notification is current.")
                        // Optionally, refresh the notification content if needed, though startTimerRoutine does it initially.
                        // updateNotification("Time left: ${formatTime(timeBankManager.getTimeSeconds())} for ${getAppNameFromString(currentMonitoredApp)}")
                        return START_STICKY
                    }

                    Log.d(TAG, "Proceeding to start/restart timer for $appPackage.")
                    stopTimerRoutine() // Stop any previous routine cleanly

                    currentMonitoredApp = appPackage // Set currentMonitoredApp BEFORE creating notification

                    // Create the initial notification for startForeground
                    // The contentText can be a generic message initially, startTimerRoutine will update it with time.
                    val initialNotificationText = "Preparing timer for ${getAppNameFromString(currentMonitoredApp)}"
                    val notification = createNotification(initialNotificationText)

                    Log.d(TAG, "Calling startForeground for $appPackage")
                    startForeground(NOTIFICATION_ID, notification)
                    Log.d(TAG, "Called startForeground. Notification ID: $NOTIFICATION_ID")

                    startTimerRoutine() // This will launch a new coroutine which will update the notification with time
                    Log.i(TAG, "Timer routine (re)started for $appPackage")

                } else {
                    Log.w(TAG, "ACTION_START_TIMER but no app package provided.")
                    stopSelfIfNotMonitoring()
                }
            }
            ACTION_STOP_TIMER -> {
                Log.i(TAG, "onStartCommand: ACTION_STOP_TIMER received.")
                stopTimerRoutine()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                currentMonitoredApp = null // Clear monitored app AFTER stopping everything
            }
            else -> {
                // This handles null intent if service is restarted, or unknown actions
                Log.w(TAG, "Unknown action ($intent?.action) or null intent. Current monitored: $currentMonitoredApp. Job active: ${serviceJob?.isActive}")
                stopSelfIfNotMonitoring() // Check if we should stop
            }
        }
        return START_STICKY
    }

    private fun startTimerRoutine() {
        stopTimerRoutine() // Ensure any old job is definitely stopped and nulled before starting a new one

        // Create a new job within the serviceScope
        serviceJob = serviceScope.launch {
            val appBeingMonitored = currentMonitoredApp // Capture for use in this coroutine instance
            Log.d(TAG, "startTimerRoutine: Coroutine launched for $appBeingMonitored. Job active: ${this.coroutineContext.job.isActive}")

            try {
                val initialTimeSeconds = timeBankManager.getTimeSeconds()
                if (initialTimeSeconds <= 0) {
                    Log.i(TAG, "Initial time is zero or less for $appBeingMonitored. Sending time expired broadcast and stopping.")
                    sendBroadcast(Intent(BROADCAST_TIME_EXPIRED).apply {
                        putExtra(EXTRA_APP_PACKAGE, appBeingMonitored)
                    })
                    stopSelf() // Stop the service
                    return@launch // Exit this coroutine
                }

                // Update notification with initial time. Use the captured appBeingMonitored.
                updateNotification("Time left: ${formatTime(initialTimeSeconds)} for ${getAppNameFromString(appBeingMonitored)}")


                while (isActive) { // 'isActive' is a property of CoroutineScope, true as long as the job is active
                    delay(1000) // Wait for 1 second

                    if (!isActive) { // Re-check after delay, in case job was cancelled during delay
                        Log.d(TAG, "Coroutine for $appBeingMonitored became inactive after delay.")
                        break
                    }

                    // Decrement time using TimeBankManager
                    val timeUsedSuccessfully = timeBankManager.useTime(1)
                    val newRemainingTime = timeBankManager.getTimeSeconds() // Get current time after attempted use

                    if (timeUsedSuccessfully) {
                        Log.d(TAG, "Time tick for $appBeingMonitored. Remaining: $newRemainingTime s")
                        updateNotification("Time left: ${formatTime(newRemainingTime)} for ${getAppNameFromString(appBeingMonitored)}")

                        if (newRemainingTime <= 0) {
                            Log.i(TAG, "Time is up in coroutine for $appBeingMonitored. Sending broadcast.")
                            sendBroadcast(Intent(BROADCAST_TIME_EXPIRED).apply {
                                putExtra(EXTRA_APP_PACKAGE, appBeingMonitored)
                            })
                            stopSelf() // Stop the service itself
                            break      // Exit loop
                        }
                    } else {
                        // This case means useTime(1) failed, which could happen if time was already <=0
                        // or some other issue with SharedPreferences commit.
                        Log.w(TAG, "Failed to use 1 second for $appBeingMonitored. Current time: $newRemainingTime s.")
                        if (newRemainingTime <= 0) {
                            Log.i(TAG, "Time found to be zero or less after failed useTime for $appBeingMonitored. Sending broadcast.")
                            sendBroadcast(Intent(BROADCAST_TIME_EXPIRED).apply {
                                putExtra(EXTRA_APP_PACKAGE, appBeingMonitored)
                            })
                            stopSelf()
                            break
                        }
                        // If time is still positive but useTime failed, something is fishy.
                        // For now, loop will continue, and it will be caught in the next iteration or if time goes to 0.
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Timer coroutine for $appBeingMonitored was cancelled.")
                // Notification will be removed if stopForeground is called when service stops or is re-started
            } catch (e: Exception) {
                Log.e(TAG, "Error in timer coroutine for $appBeingMonitored", e)
                stopSelf() // Stop service on unexpected error
            } finally {
                Log.d(TAG, "Timer coroutine finally block for $appBeingMonitored. Job active: ${this.coroutineContext.job.isActive}")
                // If the service is stopping, onDestroy will also call stopTimerRoutine.
                // If only this job is ending (e.g. time up), stopSelf() should have been called.
            }
        }
        // Log outside the launch block, to see the state of serviceJob immediately after launch is called
        Log.d(TAG, "Exiting startTimerRoutine for $currentMonitoredApp. ServiceJob is now: ${if(serviceJob?.isActive == true) "active" else "inactive/null"}")
    }

    private fun stopTimerRoutine() {
        if (serviceJob?.isActive == true) {
            Log.d(TAG, "Stopping active timer coroutine job for $currentMonitoredApp.")
            serviceJob?.cancel() // Cancel the job
        } else {
            Log.d(TAG, "Timer coroutine job already inactive or null for $currentMonitoredApp.")
        }
        serviceJob = null // Set to null after cancelling or if it was already null/inactive
    }

    private fun createNotification(contentText: String): Notification { // Keep existing signature if you prefer
        // Or, more specific:
// private fun createNotification(monitoringApp: String, timeLeftText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingIntentFlags
        )

        val title = if (currentMonitoredApp != null) "Monitoring: ${getAppNameFromString(currentMonitoredApp)}" else "Timer Service Active"

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title) // Use dynamic title
            .setContentText(contentText) // This will be the "Time left: MM:SS"
            .setSmallIcon(R.drawable.ic_tiny_pushup_patrol_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Might help with "noisy" if updates are frequent but not for new foreground call

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPriority(NotificationManager.IMPORTANCE_LOW)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Timer Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            serviceChannel.setSound(null, null)
            serviceChannel.enableVibration(false)

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun updateNotification(contentText: String, appName: String? = null) { // appName is optional for simpler calls
        // The createNotification above will use currentMonitoredApp for the title
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        //Log.d(TAG, "Notification updated with text: $contentText for ${appName ?: currentMonitoredApp}")
    }

    private fun formatTime(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun getAppNameFromString(packageName: String?): String {
        if (packageName == null) return "App"
        return try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Failed to get app name for $packageName, using package name.", e)
            packageName // Fallback to package name
        }
    }

    private fun stopSelfIfNotMonitoring() {
        // Check if the service job is active AND we have a current monitored app.
        // If not, it means the service is idle or in an invalid state.
        if (serviceJob?.isActive != true || currentMonitoredApp == null) {
            Log.w(TAG, "stopSelfIfNotMonitoring: Service is not actively monitoring or has no target. Stopping service.")
            Log.d(TAG, "stopSelfIfNotMonitoring: serviceJob active: ${serviceJob?.isActive}, currentMonitoredApp: $currentMonitoredApp")

            // Stop the foreground service and remove the notification.
            // STOP_FOREGROUND_REMOVE is available from API 24.
            // For older versions, stopForeground(true) is equivalent to remove.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true) // true = remove notification
            }
            stopSelf() // Stop the service itself
        } else {
            Log.d(TAG, "stopSelfIfNotMonitoring: Service is actively monitoring $currentMonitoredApp. Not stopping.")
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "TimerService destroyed.")
        stopTimerRoutine() // Ensure coroutine is stopped
        super.onDestroy()
    }
}