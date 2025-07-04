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
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // Added SupervisorJob
    private var currentMonitoredApp: String? = null
    private var currentMonitoredAppFriendlyName: String? = null // For notification display

    @Volatile // Ensure visibility across threads
    private var isPaused: Boolean = false
    private var remainingTimeSecondsOnPause: Int = 0

    companion object {
        private const val TAG = "TimerService"
        const val ACTION_START_TIMER = "com.example.pushuppatrol.ACTION_START_TIMER"
        const val ACTION_STOP_TIMER = "com.example.pushuppatrol.ACTION_STOP_TIMER"
        const val ACTION_PAUSE_TIMER = "com.example.pushuppatrol.ACTION_PAUSE_TIMER" // New
        const val ACTION_RESUME_TIMER = "com.example.pushuppatrol.ACTION_RESUME_TIMER" // New
        const val EXTRA_APP_PACKAGE = "com.example.pushuppatrol.EXTRA_APP_PACKAGE"

        // Use the channel ID from MainApplication if defined there, otherwise keep this
        // const val NOTIFICATION_CHANNEL_ID = "TimerServiceChannel" // Ensure this matches MainApplication
        // For consistency with typical Android examples:
        private const val NOTIFICATION_CHANNEL_ID = MainApplication.TIMER_SERVICE_CHANNEL_ID


        private const val NOTIFICATION_ID = 123

        const val BROADCAST_TIME_EXPIRED = "com.example.pushuppatrol.TIME_EXPIRED"
    }

    override fun onCreate() {
        super.onCreate()
        timeBankManager = TimeBankManager(applicationContext)
        createNotificationChannel() // Ensure channel is created
        Log.d(TAG, "TimerService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received: ${intent?.action}, App: ${intent?.getStringExtra(EXTRA_APP_PACKAGE)}, Current Monitored: $currentMonitoredApp, Paused: $isPaused, Job Active: ${serviceJob?.isActive}")

        val appPackage = intent?.getStringExtra(EXTRA_APP_PACKAGE)

        when (intent?.action) {
            ACTION_START_TIMER -> {
                if (appPackage != null) {
                    if (appPackage == currentMonitoredApp && serviceJob?.isActive == true && !isPaused) {
                        Log.d(TAG, "Timer already running for $appPackage. Ensuring notification is current.")
                        updateNotificationContent(timeBankManager.getTimeSeconds())
                        return START_STICKY
                    }
                    if (appPackage == currentMonitoredApp && isPaused) {
                        Log.d(TAG, "Timer was paused for $appPackage, but received START. Effectively resuming.")
                        handleResumeTimer()
                        return START_STICKY
                    }

                    Log.d(TAG, "Proceeding to start/restart timer for $appPackage.")
                    stopTimerRoutine() // Stop any previous routine cleanly

                    currentMonitoredApp = appPackage
                    currentMonitoredAppFriendlyName = getAppNameFromString(currentMonitoredApp)
                    isPaused = false // Ensure not paused when starting fresh

                    val initialTimeSeconds = timeBankManager.getTimeSeconds()
                    val initialTitle: String
                    val initialContent: String

                    if (initialTimeSeconds > 0) {
                        initialTitle = "Preparing: $currentMonitoredAppFriendlyName"
                        initialContent = "Time available: ${formatTime(initialTimeSeconds)}"
                    } else {
                        initialTitle = "Blocked: $currentMonitoredAppFriendlyName"
                        initialContent = "No time available" // Or "Time expired"
                    }

                    // Start foreground with this initial, more informative notification
                    startForeground(NOTIFICATION_ID, createNotification(initialTitle, initialContent))
                    Log.d(TAG, "Called startForeground for $appPackage. Title: \"$initialTitle\", Content: \"$initialContent\"")

                    if (initialTimeSeconds > 0) {
                        startTimerRoutine(initialTimeSeconds)
                        Log.i(TAG, "Timer routine (re)started for $appPackage with $initialTimeSeconds seconds.")
                    } else {
                        Log.w(TAG, "No time available to start timer for $appPackage.")
                        AppBlockerEventManager.reportTimeExpired(appPackage)
                        stopSelfSafely() // This should also remove the notification if it stops the service
                    }

                } else {
                    Log.w(TAG, "ACTION_START_TIMER but no app package provided.")
                    stopSelfSafely()
                }
            }
            ACTION_STOP_TIMER -> {
                Log.i(TAG, "ACTION_STOP_TIMER received for $appPackage (current: $currentMonitoredApp).")
                // If the stop is for the current app, or a general stop (appPackage == null)
                if (appPackage == null || appPackage == currentMonitoredApp) {
                    stopTimerRoutine() // This will also handle saving remaining time if paused
                    removeNotificationAndStopForeground()
                    currentMonitoredApp = null
                    currentMonitoredAppFriendlyName = null
                    stopSelf() // Explicitly stop the service
                } else {
                    Log.w(TAG, "ACTION_STOP_TIMER for $appPackage, but currently monitoring $currentMonitoredApp. Ignoring.")
                }
            }
            ACTION_PAUSE_TIMER -> {
                if (appPackage != null && appPackage == currentMonitoredApp && serviceJob?.isActive == true && !isPaused) {
                    handlePauseTimer()
                } else {
                    Log.w(TAG, "ACTION_PAUSE_TIMER: Conditions not met. App: $appPackage, Current: $currentMonitoredApp, Paused: $isPaused, Job: ${serviceJob?.isActive}")
                }
            }
            ACTION_RESUME_TIMER -> {
                if (appPackage != null && appPackage == currentMonitoredApp && isPaused) {
                    handleResumeTimer()
                } else {
                    Log.w(TAG, "ACTION_RESUME_TIMER: Conditions not met. App: $appPackage, Current: $currentMonitoredApp, Paused: $isPaused")
                }
            }
            else -> {
                Log.w(TAG, "Unknown action ($intent?.action) or null intent. Current monitored: $currentMonitoredApp. Job active: ${serviceJob?.isActive}")
                stopSelfSafely()
            }
        }
        return START_STICKY
    }

    private fun handlePauseTimer() {
        Log.d(TAG, "Pausing timer for $currentMonitoredApp. Job active: ${serviceJob?.isActive}")
        if (serviceJob?.isActive == true) { // Ensure there's an active job to pause
            remainingTimeSecondsOnPause = timeBankManager.getTimeSeconds() // Get current time from bank
            isPaused = true // Set paused before cancelling job to influence its cleanup/finalization
            serviceJob?.cancel(CancellationException("Timer paused by user action")) // Cancel the coroutine
            serviceJob = null // Nullify the job after cancelling

            // Persist the remaining time explicitly when pausing
            // timeBankManager.updateTime(remainingTimeSecondsOnPause) // Or useTime(0) to just save.
            // No, TimeBankManager should reflect time used *up to* the pause.
            // The value from timeBankManager.getTimeSeconds() IS the remaining time.

            Log.i(TAG, "Timer paused for $currentMonitoredApp. Remaining time: $remainingTimeSecondsOnPause s")
            updateNotificationContent(remainingTimeSecondsOnPause) // Update notification to "Paused" state
        } else {
            Log.w(TAG, "Tried to pause, but no active timer job for $currentMonitoredApp.")
        }
    }

    private fun handleResumeTimer() {
        Log.d(TAG, "Resuming timer for $currentMonitoredApp. Previously remaining: $remainingTimeSecondsOnPause s")
        if (isPaused && currentMonitoredApp != null) {
            isPaused = false
            // Re-fetch from TimeBankManager as the source of truth,
            // or use remainingTimeSecondsOnPause if that's more reliable after other interactions
            val timeToResumeWith = timeBankManager.getTimeSeconds() // Or remainingTimeSecondsOnPause

            if (timeToResumeWith > 0) {
                startTimerRoutine(timeToResumeWith)
                Log.i(TAG, "Timer resumed for $currentMonitoredApp with $timeToResumeWith s")
            } else {
                Log.w(TAG, "Resuming timer for $currentMonitoredApp, but no time left ($timeToResumeWith s).")
                AppBlockerEventManager.reportTimeExpired(currentMonitoredApp)
                stopSelfSafely()
            }
        } else {
            Log.w(TAG, "Tried to resume, but not paused or no current app. Current: $currentMonitoredApp, Paused: $isPaused")
        }
    }


    private fun startTimerRoutine(initialTimeSeconds: Int) {
        stopTimerRoutine() // Ensure any old job is stopped. isPaused should be false here.
        isPaused = false // Explicitly set

        val appBeingMonitored = currentMonitoredApp

        if (appBeingMonitored == null) {
            Log.e(TAG, "startTimerRoutine: appBeingMonitored is null. Cannot start.")
            stopSelfSafely()
            return
        }

        Log.d(TAG, "startTimerRoutine: Launching coroutine for $appBeingMonitored with $initialTimeSeconds s.")
        updateNotificationContent(initialTimeSeconds) // Initial notification update

        serviceJob = serviceScope.launch {
            var currentTimeSeconds = initialTimeSeconds
            Log.d(TAG, "Coroutine started for $appBeingMonitored. Initial time: $currentTimeSeconds s. Paused: $isPaused. Active: $isActive")

            try {
                while (isActive && !isPaused && currentTimeSeconds > 0) { // Check isPaused and isActive inside loop
                    delay(1000) // Wait for 1 second

                    if (!isActive || isPaused) { // Re-check after delay
                        Log.d(TAG, "Coroutine for $appBeingMonitored breaking loop. Active: $isActive, Paused: $isPaused")
                        break
                    }

                    val timeUsedSuccessfully = timeBankManager.useTime(1) // Decrement time
                    currentTimeSeconds = timeBankManager.getTimeSeconds() // Get new remaining time

                    if (timeUsedSuccessfully) {
                        Log.d(TAG, "Time tick for $appBeingMonitored. Remaining: $currentTimeSeconds s. Paused: $isPaused")
                        if (!isPaused) { // Only update notification if not paused during this check
                            updateNotificationContent(currentTimeSeconds)
                        }
                    } else {
                        Log.w(TAG, "Failed to use 1 second for $appBeingMonitored. Current time: $currentTimeSeconds s.")
                        // If useTime failed but time is still positive, it's odd. If zero, it'll be caught below.
                    }

                    if (currentTimeSeconds <= 0) {
                        Log.i(TAG, "Time is up in coroutine for $appBeingMonitored.")
                        AppBlockerEventManager.reportTimeExpired(appBeingMonitored)
                        break // Exit loop
                    }
                } // End of while loop

                // After loop completion or break:
                if (isPaused) {
                    remainingTimeSecondsOnPause = currentTimeSeconds // Store final time before pause breaks loop
                    Log.d(TAG, "Coroutine for $appBeingMonitored ended due to pause. Remaining: $remainingTimeSecondsOnPause s.")
                } else if (currentTimeSeconds <= 0) {
                    Log.d(TAG, "Coroutine for $appBeingMonitored ended due to time running out.")
                    // Time expired broadcast is already sent if currentTimeSeconds <= 0 led to break
                } else if (!isActive) {
                    Log.d(TAG, "Coroutine for $appBeingMonitored ended due to job cancellation (not pause).")
                }

            } catch (e: CancellationException) {
                // This will catch cancellation from stopTimerRoutine or if handlePauseTimer directly cancels.
                Log.i(TAG, "Timer coroutine for $appBeingMonitored was cancelled. Message: ${e.message}. Is Paused: $isPaused")
                if (isPaused) {
                    // remainingTimeSecondsOnPause should have been set by handlePauseTimer
                    // or by the check inside the loop before breaking.
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in timer coroutine for $appBeingMonitored", e)
                // stopSelfSafely() // Consider if service should stop on all errors
            } finally {
                Log.d(TAG, "Timer coroutine finally block for $appBeingMonitored. Job active: ${coroutineContext.job.isActive}, Is Paused: $isPaused")
                // If the job is completing and not because of a pause, and time is up, the service might stop.
                // If it was paused, the service should remain running.
                if (!isPaused && timeBankManager.getTimeSeconds() <= 0) {
                    // Ensure time expired is reported if loop exited without explicit report
                    if (currentMonitoredApp == appBeingMonitored) { // only if this app is still the target
                        AppBlockerEventManager.reportTimeExpired(appBeingMonitored)
                    }
                    stopSelfSafely()
                }
            }
        }
        Log.d(TAG, "Exiting startTimerRoutine for $currentMonitoredApp. ServiceJob is now: ${if (serviceJob?.isActive == true) "active" else "inactive/null"}")
    }

    private fun stopTimerRoutine() {
        Log.d(TAG, "stopTimerRoutine called for $currentMonitoredApp. Is Paused: $isPaused. Job active: ${serviceJob?.isActive}")
        if (isPaused) {
            // If stopping while paused, we need to ensure the remainingTimeSecondsOnPause is accurate
            // and potentially update TimeBankManager with this value.
            // For now, assume remainingTimeSecondsOnPause is the value to respect.
            // timeBankManager.updateTime(remainingTimeSecondsOnPause) // Or useTime(0)
            Log.d(TAG, "Stopping a paused timer. Time that was remaining: $remainingTimeSecondsOnPause s for $currentMonitoredApp")
        }

        if (serviceJob?.isActive == true) {
            Log.d(TAG, "Cancelling active timer coroutine job for $currentMonitoredApp.")
            serviceJob?.cancel(CancellationException("Timer stopped by explicit action or new timer start"))
        }
        serviceJob = null
        isPaused = false // Reset paused state when timer routine is fully stopped
        remainingTimeSecondsOnPause = 0
        Log.d(TAG, "Timer routine stopped. Monitored app: $currentMonitoredApp, Paused state reset.")
    }

    private fun updateNotificationContent(timeSeconds: Int) {
        val appNameToDisplay = currentMonitoredAppFriendlyName ?: getAppNameFromString(currentMonitoredApp)
        val timeFormatted = formatTime(timeSeconds)

        // Determine title and content based on state
        val title: String
        val content: String

        if (isPaused) {
            title = "Paused: $appNameToDisplay"
            content = "Remaining: $timeFormatted"
        } else if (serviceJob?.isActive == true || (currentMonitoredApp != null && timeSeconds > 0) ) { // Actively timing or about to time with time available
            title = "Timing: $appNameToDisplay"
            content = "Time left: $timeFormatted"
        } else if (currentMonitoredApp != null && timeSeconds <= 0) { // No time left for the app
            title = "Blocked: $appNameToDisplay"
            content = "Time expired"
        }
        else { // Default / Initializing - should ideally be more specific if possible
            title = "Preparing: $appNameToDisplay"
            content = "Checking time..." // Or show initial time if readily available
        }

        val notification = createNotification(title, content) // Pass both title and content
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Notification updated. Title: \"$title\", Content: \"$content\"")
    }


    private fun createNotification(title: String, contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingIntentFlags
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title) // Use the passed title
            .setContentText(contentText) // Use the passed contentText
            .setSmallIcon(R.drawable.ic_tiny_pushup_patrol_logo) // Ensure this is a monochrome icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPriority(NotificationManager.IMPORTANCE_LOW)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Timer Service Channel", // User-visible name in App Info
                NotificationManager.IMPORTANCE_LOW // Low importance for ongoing status
            ).apply {
                description = "Shows active timer for monitored apps"
                setSound(null, null) // No sound for updates
                enableVibration(false) // No vibration for updates
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Notification channel $NOTIFICATION_CHANNEL_ID created/ensured.")
        }
    }

    private fun formatTime(totalSeconds: Int): String {
        if (totalSeconds < 0) return "00:00" // Handle potential negative values gracefully
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
            packageName
        }
    }

    private fun removeNotificationAndStopForeground() {
        Log.d(TAG, "Removing notification and stopping foreground state.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopSelfSafely() {
        // Only stop if not paused and no active job for a current app
        if (!isPaused && (serviceJob?.isActive != true || currentMonitoredApp == null)) {
            Log.w(TAG, "stopSelfSafely: Conditions met. Stopping service. Paused: $isPaused, Job Active: ${serviceJob?.isActive}, App: $currentMonitoredApp")
            removeNotificationAndStopForeground()
            currentMonitoredApp = null
            currentMonitoredAppFriendlyName = null
            stopSelf()
        } else {
            Log.d(TAG, "stopSelfSafely: Conditions not met. Service kept running. Paused: $isPaused, Job Active: ${serviceJob?.isActive}, App: $currentMonitoredApp")
        }
    }


    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "TimerService destroyed. Current app: $currentMonitoredApp, Paused: $isPaused")
        stopTimerRoutine() // Ensure coroutine is stopped and isPaused is reset
        serviceScope.cancel() // Cancel all coroutines in this scope
        // Notification should be removed by stopTimerRoutine or by system if service is killed
        removeNotificationAndStopForeground() // Explicitly remove notification
        super.onDestroy()
    }
}