package com.example.pushuppatrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.io.path.name

// import kotlin.io.path.name // Not needed from what I see

class AppBlockerService : AccessibilityService(), TimeExpirationListener {

    private lateinit var timeBankManager: TimeBankManager
    // private lateinit var gracePeriodManager: GracePeriodManager // Commented out as it's not used in current logic provided
    private lateinit var sharedPreferences: SharedPreferences
    private var lockedAppPackages: Set<String> = emptySet()
    private var currentForegroundApp: String? = null

    // --- State for SystemUI Pause/Resume ---
    private var isTimerPausedForSystemUI: Boolean = false
    private var appPackagePausedForSystemUI: String? = null
    // --- End State for SystemUI Pause/Resume ---

    private var lastTimerStartedForPackage: String? = null // App for which TimerService.ACTION_START_TIMER was last sent
    private var lastTimerStartTimeMillis: Long = 0 // Used for debouncing START_TIMER
    private val timerStartDebounceMillis = 500 // 0.5 seconds

    companion object {
        private const val TAG = "AppBlockerService"
        const val PREFS_NAME = "AppBlockerPrefs"
        const val KEY_LOCKED_APPS = "locked_app_packages"
        const val ACTION_LOCKED_APPS_UPDATED = "com.example.pushuppatrol.LOCKED_APPS_UPDATED"
        private const val SYSTEM_UI_PACKAGE_NAME = "com.android.systemui"
    }

    // --- Broadcast Receivers ---
    private val timeExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TimerService.BROADCAST_TIME_EXPIRED) {
                val expiredAppPackage = intent.getStringExtra(TimerService.EXTRA_APP_PACKAGE)
                Log.i(TAG, "Time expired broadcast received (from TimerService) for app: $expiredAppPackage.")
                // AppBlockerEventManager handles the event, which then calls onTimeExpired below
                // No direct action here, relies on onTimeExpired via AppBlockerEventManager
            }
        }
    }

    private val lockedAppsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_LOCKED_APPS_UPDATED) {
                Log.d(TAG, "LOCKED_APPS_UPDATED broadcast received. Reloading list.")
                loadLockedAppsList()
                // If a locked app is currently in foreground, re-evaluate
                currentForegroundApp?.let {
                    Log.d(TAG, "Re-evaluating current foreground app $it after locked apps update.")
                    handleAppInForeground(it, true) // Force re-evaluation
                }
            }
        }
    }

    // --- Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service is being created.")
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        timeBankManager = TimeBankManager(applicationContext)
        // gracePeriodManager = GracePeriodManager(applicationContext) // Initialize if needed

        loadLockedAppsList()
        Log.d(TAG, "Initial locked apps: $lockedAppPackages")

        AppBlockerEventManager.setTimeExpirationListener(this)

        val intentFilterLockedApps = IntentFilter(ACTION_LOCKED_APPS_UPDATED)
        val intentFilterTimeExpired = IntentFilter(TimerService.BROADCAST_TIME_EXPIRED)

        registerReceiver(lockedAppsUpdateReceiver, intentFilterLockedApps, Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(timeExpiredReceiver, intentFilterTimeExpired, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Broadcast receivers registered.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        setServiceInfo(serviceInfo)
        Log.i(TAG, "onServiceConnected: Service connected and configured.")
        loadLockedAppsList()
        Toast.makeText(this, "Push-up Patrol Blocker Active", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: Service interrupted.")
        // AppBlockerEventManager.setTimeExpirationListener(null) // Should be handled in onDestroy
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy: Service is being destroyed.")
        AppBlockerEventManager.setTimeExpirationListener(null)
        try {
            unregisterReceiver(lockedAppsUpdateReceiver)
            unregisterReceiver(timeExpiredReceiver)
            Log.d(TAG, "Broadcast receivers unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receivers not registered or already unregistered.", e)
        }
        // Ensure any active timer is stopped if service is destroyed
        if (lastTimerStartedForPackage != null || isTimerPausedForSystemUI) {
            Log.w(TAG, "Service destroying. Forcing stop of any active/paused timer for ${lastTimerStartedForPackage ?: appPackagePausedForSystemUI}")
            sendTimerCommand(TimerService.ACTION_STOP_TIMER, lastTimerStartedForPackage ?: appPackagePausedForSystemUI)
            clearSystemUiPauseState()
            lastTimerStartedForPackage = null
        }
    }

    // --- Core Logic ---
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == null) {
            // Log.v(TAG, "Null event or packageName in onAccessibilityEvent") // Too noisy
            return
        }

        // We are primarily interested in window state changes for foreground app detection
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Log.v(TAG, "Ignoring event type: ${AccessibilityEvent.eventTypeToString(event.eventType)}") // Too noisy
            return
        }

        val packageName = event.packageName.toString()
        val className = event.className?.toString() // Can be null

        // Log.d(TAG, "onAccessibilityEvent: Pkg: $packageName, Class: $className, EventType: ${AccessibilityEvent.eventTypeToString(event.eventType)}, CurrentFG: $currentForegroundApp, PausedSystemUI: $isTimerPausedForSystemUI for $appPackagePausedForSystemUI")

        // --- Handle SystemUI (Notifications/Quick Settings) ---
        if (packageName == SYSTEM_UI_PACKAGE_NAME) {
            // If SystemUI comes up AND a timer is running for a locked app (not already paused for SystemUI)
            if (!isTimerPausedForSystemUI && lastTimerStartedForPackage != null && lockedAppPackages.contains(lastTimerStartedForPackage!!)) {
                Log.i(TAG, "SystemUI detected ($className). Pausing timer for $lastTimerStartedForPackage.")
                sendTimerCommand(TimerService.ACTION_PAUSE_TIMER, lastTimerStartedForPackage)
                appPackagePausedForSystemUI = lastTimerStartedForPackage
                isTimerPausedForSystemUI = true
                // Don't update currentForegroundApp to SystemUI yet, as we want to know what was under it.
            } else {
                // Log.d(TAG, "SystemUI detected, but no timer to pause or already paused. isTimerPaused: $isTimerPausedForSystemUI, lastTimerPkg: $lastTimerStartedForPackage")
            }
            return // SystemUI itself is not managed as a timed app
        }

        // --- SystemUI is no longer foreground, or a different app has appeared ---
        if (isTimerPausedForSystemUI) {
            Log.i(TAG, "SystemUI interaction ended. New foreground app: $packageName. App that was paused: $appPackagePausedForSystemUI")
            if (appPackagePausedForSystemUI != null && appPackagePausedForSystemUI == packageName) {
                // The same app that was under SystemUI is back. Resume its timer.
                Log.i(TAG, "Resuming timer for $appPackagePausedForSystemUI as it's back in foreground.")
                sendTimerCommand(TimerService.ACTION_RESUME_TIMER, appPackagePausedForSystemUI)
                // lastTimerStartedForPackage should still be appPackagePausedForSystemUI
            } else {
                // A different app came to foreground (or home screen, or our own app).
                // The timer for appPackagePausedForSystemUI should be definitively stopped.
                if (appPackagePausedForSystemUI != null) {
                    Log.i(TAG, "$appPackagePausedForSystemUI is not the new foreground ($packageName). Ensuring its timer is stopped.")
                    sendTimerCommand(TimerService.ACTION_STOP_TIMER, appPackagePausedForSystemUI)
                    if (lastTimerStartedForPackage == appPackagePausedForSystemUI) {
                        lastTimerStartedForPackage = null // Clear it as it's now stopped
                    }
                }
                // Fall through to normal processing for the new 'packageName'.
            }
            clearSystemUiPauseState() // Reset SystemUI pause state
            // DO NOT return here, let the new foreground app (packageName) be processed.
        }

        // Update currentForegroundApp (only if it truly changes and it's a window state change event)
        if (currentForegroundApp != packageName && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            Log.d(TAG, "Foreground app definitively changed: $currentForegroundApp -> $packageName (Class: $className)")
            currentForegroundApp = packageName
        } else if (currentForegroundApp == null && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            currentForegroundApp = packageName // Initial foreground app
            Log.d(TAG, "Initial foreground app set: $currentForegroundApp (Class: $className)")
        }


        // Ignore events from own app or launcher to prevent self-blocking loops
        if (packageName == applicationContext.packageName || isLauncher(packageName)) {
            if (packageName == applicationContext.packageName &&
                className != PushupActivity::class.java.name &&
                className != InterstitialBlockActivity::class.java.name && // Check Interstitial activity
                className != MainActivity::class.java.name) { // And MainActivity
                // Log.d(TAG, "Own app ($className) is in foreground, but not a utility activity.")
                // This case might be an internal screen of your app that should still stop other timers.
            }

            // If our app (any part of it) or launcher comes to foreground, ensure any active timer for other apps is stopped.
            if (lastTimerStartedForPackage != null && lastTimerStartedForPackage != applicationContext.packageName) {
                Log.d(TAG, "Our app ($packageName) or launcher is in foreground. Stopping timer if it was running for $lastTimerStartedForPackage.")
                sendTimerCommand(TimerService.ACTION_STOP_TIMER, lastTimerStartedForPackage)
                lastTimerStartedForPackage = null // Timer is stopped
            }
            clearSystemUiPauseState() // Also clear if our app/launcher comes up
            return
        }

        // Main logic for handling the foreground app
        handleAppInForeground(packageName)
    }

    private fun handleAppInForeground(packageName: String, forceReevaluation: Boolean = false) {
        // Log.d(TAG, "handleAppInForeground: $packageName, LastTimer: $lastTimerStartedForPackage, PausedForUI: $isTimerPausedForSystemUI for $appPackagePausedForSystemUI")

        if (isTimerPausedForSystemUI && appPackagePausedForSystemUI == packageName) {
            // This case should ideally be handled by the resume logic before this function is called.
            // However, as a safeguard: if we are paused for this app, it means we should be resuming.
            Log.d(TAG, "handleAppInForeground: App $packageName was paused for SystemUI. Attempting resume.")
            sendTimerCommand(TimerService.ACTION_RESUME_TIMER, packageName)
            clearSystemUiPauseState()
            lastTimerStartedForPackage = packageName // Ensure this is set for resumed app
            return // Resumed, no further action needed here for this event
        }


        if (lockedAppPackages.contains(packageName)) {
            // --- This is a LOCKED APP ---
            Log.d(TAG, "Locked app in foreground: $packageName")

            val remainingTimeSeconds = timeBankManager.getTimeSeconds()
            Log.d(TAG, "Time available: $remainingTimeSeconds seconds for $packageName")

            if (remainingTimeSeconds <= 0) {
                Log.i(TAG, "Time is zero for locked app $packageName. Launching Interstitial.")
                // Stop any timer that might be lingering (e.g., if broadcast was missed or for another app)
                if (lastTimerStartedForPackage != null) {
                    sendTimerCommand(TimerService.ACTION_STOP_TIMER, lastTimerStartedForPackage)
                }
                lastTimerStartedForPackage = null // Ensure it's clear before blocking
                launchInterstitialBlockActivity(packageName)
            } else {
                // Time available. Start TimerService if not already running for this app, or if forced.
                if (packageName != lastTimerStartedForPackage || forceReevaluation) {
                    Log.d(TAG, "Time available for $packageName. Ensuring TimerService is running.")
                    startTimerServiceForLockedApp(packageName)
                } else {
                    Log.d(TAG, "Timer already considered running for $packageName. No action.")
                }
            }
        } else {
            // --- This is a NON-LOCKED APP ---
            Log.d(TAG, "Non-locked app in foreground: $packageName.")
            if (lastTimerStartedForPackage != null) {
                Log.d(TAG, "Stopping timer as non-locked app ($packageName) is foreground. Was running for $lastTimerStartedForPackage.")
                sendTimerCommand(TimerService.ACTION_STOP_TIMER, lastTimerStartedForPackage)
                lastTimerStartedForPackage = null
            }
            clearSystemUiPauseState() // If a non-locked app comes up, clear any SystemUI pause state
        }
    }


    // --- TimeExpirationListener Implementation ---
    override fun onTimeExpired(expiredAppPackage: String?) {
        Log.i(TAG, "onTimeExpired (Listener via AppBlockerEventManager): App: $expiredAppPackage. Current FG: $currentForegroundApp")
        if (isTimerPausedForSystemUI && appPackagePausedForSystemUI == expiredAppPackage) {
            Log.d(TAG, "Time expired for $expiredAppPackage while SystemUI was up or just closed. Clearing pause state.")
            clearSystemUiPauseState()
        }
        if (lastTimerStartedForPackage == expiredAppPackage) {
            lastTimerStartedForPackage = null // Clear as its time is up.
        }
        handleTimeExpirationLogic(expiredAppPackage)
    }

    private fun handleTimeExpirationLogic(expiredAppPackage: String?) {
        // This logic is called when TimerService reports time is up OR AppBlockerEventManager relays it.
        // It should block if the expired app is still in the foreground.
        if (expiredAppPackage != null &&
            currentForegroundApp == expiredAppPackage &&
            lockedAppPackages.contains(expiredAppPackage)) {

            // val event = serviceInfo?.resolveCallingPackageActivity(null) // This is a placeholder and won't work like this
            val currentActivityName = getForegroundActivityClassNameFromEvent() // Needs a reliable way if used

            val isOurOwnUtilityActivity = currentForegroundApp == applicationContext.packageName &&
                    (currentActivityName == PushupActivity::class.java.name ||
                            currentActivityName == MainActivity::class.java.name ||
                            currentActivityName == InterstitialBlockActivity::class.java.name ||
                            classNameIsOurApp(currentActivityName)) // classNameIsOurApp uses applicationContext.packageName

            if (!isOurOwnUtilityActivity) {
                Log.i(TAG, "$currentForegroundApp (locked) is still foreground after time expired. Re-locking.")
                launchInterstitialBlockActivity(currentForegroundApp)
            } else {
                Log.d(TAG, "Time expired for $currentForegroundApp, but our own activity ($currentActivityName) is foreground. No re-lock.")
            }
        } else {
            Log.i(TAG, "Time expired for $expiredAppPackage, but conditions for re-lock not met (current FG: $currentForegroundApp).")
        }
    }

    // --- Helper Methods ---
    private fun loadLockedAppsList() {
        lockedAppPackages = sharedPreferences.getStringSet(KEY_LOCKED_APPS, emptySet()) ?: emptySet()
        Log.i(TAG, "Locked apps list reloaded: $lockedAppPackages")
    }

    private fun sendTimerCommand(action: String, appPackage: String?) {
        if (appPackage == null && (action == TimerService.ACTION_PAUSE_TIMER || action == TimerService.ACTION_RESUME_TIMER || action == TimerService.ACTION_START_TIMER)) {
            Log.w(TAG, "Cannot perform $action without an appPackage.")
            return
        }
        // For ACTION_STOP_TIMER, appPackage can be null to signify a general stop,
        // but TimerService might need it to update specific app's time in bank if paused.
        // The TimerService provided now uses currentMonitoredApp for context on stop.

        Log.i(TAG, "Sending Command to TimerService: $action for App: ${appPackage ?: "N/A (General Stop?)"}")
        val serviceIntent = Intent(this, TimerService::class.java).apply {
            this.action = action
            if (appPackage != null) { // Only add if appPackage is not null
                putExtra(TimerService.EXTRA_APP_PACKAGE, appPackage)
            }
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting TimerService with action $action for $appPackage", e)
        }
    }


    private fun startTimerServiceForLockedApp(appPackage: String) {
        val currentTimeMillis = System.currentTimeMillis()
        // Debounce: If TimerService was just started for this app (and not paused), don't restart it.
        if (appPackage == lastTimerStartedForPackage && !isTimerPausedForSystemUI && (currentTimeMillis - lastTimerStartTimeMillis) < timerStartDebounceMillis) {
            Log.d(TAG, "Debouncing START_TIMER for $appPackage (recently started).")
            return
        }

        Log.d(TAG, "Requesting TimerService to START for locked app: $appPackage")
        sendTimerCommand(TimerService.ACTION_START_TIMER, appPackage)
        lastTimerStartedForPackage = appPackage // Mark this app as the one we intend to time
        if (!isTimerPausedForSystemUI) { // Only update debounce timer if not coming from a resume-like path
            lastTimerStartTimeMillis = currentTimeMillis
        }
    }

    private fun clearSystemUiPauseState() {
        if (isTimerPausedForSystemUI) {
            Log.d(TAG, "Clearing SystemUI pause state. Was paused for: $appPackagePausedForSystemUI")
        }
        isTimerPausedForSystemUI = false
        appPackagePausedForSystemUI = null
    }

    private fun launchInterstitialBlockActivity(lockedAppPackage: String?) {
        if (lockedAppPackage == null) {
            Log.w(TAG, "Attempted to launch InterstitialBlockActivity with null package name.")
            return
        }
        // Ensure timer is fully stopped for this app before showing interstitial
        if (lastTimerStartedForPackage == lockedAppPackage || appPackagePausedForSystemUI == lockedAppPackage) {
            sendTimerCommand(TimerService.ACTION_STOP_TIMER, lockedAppPackage)
            lastTimerStartedForPackage = null
            clearSystemUiPauseState()
        }

        Log.i(TAG, "Launching InterstitialBlockActivity for $lockedAppPackage.")
        val intent = Intent(this, InterstitialBlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(InterstitialBlockActivity.EXTRA_BLOCKED_APP_PACKAGE_NAME, lockedAppPackage)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching InterstitialBlockActivity for $lockedAppPackage", e)
        }
    }

    private fun isLauncher(packageName: String?): Boolean {
        if (packageName == null) return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun classNameIsOurApp(className: String?): Boolean {
        // More robust check for activities within our app
        return className != null && className.startsWith(applicationContext.packageName)
    }

    private fun getForegroundActivityClassNameFromEvent(): String? {
        // This is tricky and often unreliable from AccessibilityService alone.
        // The event.className is the primary source when an event occurs.
        // For handleTimeExpirationLogic, it's hard to get the *current* activity class name
        // without a fresh accessibility event. Relying on currentForegroundApp (package) is safer.
        Log.w(TAG, "getForegroundActivityClassNameFromEvent: This method is unreliable without a direct event.")
        return null // Placeholder, as getting this accurately on demand is complex.
    }
}