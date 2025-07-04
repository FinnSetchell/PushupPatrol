package com.example.pushuppatrol.core.blocking

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver // Keep for timeExpiredReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.pushuppatrol.core.services.TimerService
import com.example.pushuppatrol.core.time.TimeBankManager
import com.example.pushuppatrol.ui.blocking.InterstitialBlockActivity
import com.example.pushuppatrol.ui.earning.PushupActivity
import com.example.pushuppatrol.ui.main.MainActivity

// import kotlin.io.path.name // Not needed

class AppBlockerService : AccessibilityService(), TimeExpirationListener {

    private lateinit var timeBankManager: TimeBankManager
    private lateinit var sharedPreferences: SharedPreferences
    private var lockedAppPackages: Set<String> = emptySet()
    private var currentForegroundApp: String? = null

    private var isTimerPausedForSystemUI: Boolean = false
    private var appPackagePausedForSystemUI: String? = null

    private var lastTimerStartedForPackage: String? = null
    private var lastTimerStartTimeMillis: Long = 0
    private val timerStartDebounceMillis = 500

    companion object {
        private const val TAG = "AppBlockerService"
        const val PREFS_NAME = "AppBlockerPrefs"
        const val KEY_LOCKED_APPS = "locked_app_packages"
        // const val ACTION_LOCKED_APPS_UPDATED = "com.example.pushuppatrol.LOCKED_APPS_UPDATED" // <<< REMOVED (or keep if used elsewhere)
        const val ACTION_REFRESH_LOCKED_APPS = "com.example.pushuppatrol.ACTION_REFRESH_LOCKED_APPS" // <<< ADDED
        private const val SYSTEM_UI_PACKAGE_NAME = "com.android.systemui"
    }

    // --- Broadcast Receiver for TimerService ---
    private val timeExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TimerService.BROADCAST_TIME_EXPIRED) {
                val expiredAppPackage = intent.getStringExtra(TimerService.EXTRA_APP_PACKAGE)
                Log.i(TAG, "Time expired broadcast received (from TimerService) for app: $expiredAppPackage.")
                AppBlockerEventManager.reportTimeExpired(expiredAppPackage) // Directly notify manager
            }
        }
    }

    // --- REMOVED lockedAppsUpdateReceiver ---
    // private val lockedAppsUpdateReceiver = object : BroadcastReceiver() { ... }

    // --- Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service is being created.")
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        timeBankManager = TimeBankManager(applicationContext)

        loadLockedAppsList() // Load initial list
        Log.d(TAG, "Initial locked apps after onCreate load: $lockedAppPackages")

        AppBlockerEventManager.setTimeExpirationListener(this)

        // --- MODIFIED: Register only timeExpiredReceiver ---
        val intentFilterTimeExpired = IntentFilter(TimerService.BROADCAST_TIME_EXPIRED)
        registerReceiver(timeExpiredReceiver, intentFilterTimeExpired, Context.RECEIVER_NOT_EXPORTED)
        // registerReceiver(lockedAppsUpdateReceiver, intentFilterLockedApps, Context.RECEIVER_NOT_EXPORTED) // <<< REMOVED
        Log.d(TAG, "TimeExpired broadcast receiver registered.")
    }

    // <<< ADDED/MODIFIED onStartCommand to handle the new action from AppSelectionActivity >>>
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand received action: ${intent?.action}")

        when (intent?.action) {
            ACTION_REFRESH_LOCKED_APPS -> {
                Log.i(TAG, "ACTION_REFRESH_LOCKED_APPS received. Reloading locked apps list.")
                loadLockedAppsList()
                // If a locked app is currently in foreground, re-evaluate its status
                currentForegroundApp?.let { fgApp ->
                    if (lockedAppPackages.contains(fgApp) || lastTimerStartedForPackage == fgApp) {
                        Log.d(TAG, "Re-evaluating current foreground app $fgApp after REFRESH action.")
                        handleAppInForeground(fgApp, true) // Force re-evaluation
                    }
                }
            }
            else -> {
                Log.w(TAG, "onStartCommand received unhandled or null action: ${intent?.action}. This might be from TimerService starting/stopping.")
                // This service doesn't typically get started by TimerService actions itself,
                // but by accessibility enabling or our own explicit startService calls for refresh.
            }
        }
        // For an AccessibilityService that's meant to run persistently once enabled,
        // and can be started with commands, START_STICKY is appropriate.
        return START_STICKY
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

        // Load the list again here; it's a good place to ensure freshness if service restarts.
        loadLockedAppsList()
        Log.i(TAG, "Locked apps after onServiceConnected load: $lockedAppPackages")

        // If a locked app is ALREADY the current foreground app when the service connects,
        // we need to re-evaluate it immediately.
        currentForegroundApp?.let {
            Log.d(TAG, "onServiceConnected: Re-evaluating current foreground app $it after service connection.")
            handleAppInForeground(it, true) // Force re-evaluation
        }

        Toast.makeText(this, "Push-up Patrol Blocker Active", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: Service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy: Service is being destroyed.")
        AppBlockerEventManager.setTimeExpirationListener(null)
        try {
            // --- MODIFIED: Unregister only timeExpiredReceiver ---
            unregisterReceiver(timeExpiredReceiver)
            // unregisterReceiver(lockedAppsUpdateReceiver) // <<< REMOVED
            Log.d(TAG, "TimeExpired broadcast receiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered or already unregistered.", e)
        }

        if (lastTimerStartedForPackage != null || isTimerPausedForSystemUI) {
            Log.w(TAG, "Service destroying. Forcing stop of any active/paused timer for ${lastTimerStartedForPackage ?: appPackagePausedForSystemUI}")
            sendTimerCommand(TimerService.ACTION_STOP_TIMER, lastTimerStartedForPackage ?: appPackagePausedForSystemUI)
            clearSystemUiPauseState()
            lastTimerStartedForPackage = null
        }
    }

    // --- Core Logic (onAccessibilityEvent, handleAppInForeground, etc.) remains largely the same ---
    // Make sure all references to ACTION_LOCKED_APPS_UPDATED are removed if it's no longer used.
    // The main change is how the list update is triggered (via onStartCommand).

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ... (existing code)
        if (event == null || event.packageName == null) {
            // Log.v(TAG, "Null event or packageName in onAccessibilityEvent") // Too noisy
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Log.v(TAG, "Ignoring event type: ${AccessibilityEvent.eventTypeToString(event.eventType)}") // Too noisy
            return
        }

        val packageName = event.packageName.toString()
        val className = event.className?.toString()

        // --- Handle SystemUI ---
        if (packageName == SYSTEM_UI_PACKAGE_NAME) {
            if (!isTimerPausedForSystemUI && lastTimerStartedForPackage != null && lockedAppPackages.contains(lastTimerStartedForPackage!!)) {
                Log.i(TAG, "SystemUI detected ($className). Pausing timer for $lastTimerStartedForPackage.")
                sendTimerCommand(TimerService.ACTION_PAUSE_TIMER, lastTimerStartedForPackage)
                appPackagePausedForSystemUI = lastTimerStartedForPackage
                isTimerPausedForSystemUI = true
            }
            return
        }

        // --- SystemUI is no longer foreground ---
        if (isTimerPausedForSystemUI) {
            Log.i(TAG, "SystemUI interaction ended. New foreground app: $packageName. App that was paused: $appPackagePausedForSystemUI")
            if (appPackagePausedForSystemUI != null && appPackagePausedForSystemUI == packageName) {
                Log.i(TAG, "Resuming timer for $appPackagePausedForSystemUI as it's back in foreground.")
                sendTimerCommand(TimerService.ACTION_RESUME_TIMER, appPackagePausedForSystemUI)
            } else {
                if (appPackagePausedForSystemUI != null) {
                    Log.i(TAG, "$appPackagePausedForSystemUI is not the new foreground ($packageName). Ensuring its timer is stopped.")
                    sendTimerCommand(TimerService.ACTION_STOP_TIMER, appPackagePausedForSystemUI)
                    if (lastTimerStartedForPackage == appPackagePausedForSystemUI) {
                        lastTimerStartedForPackage = null
                    }
                }
            }
            clearSystemUiPauseState()
        }

        // Update currentForegroundApp
        if (currentForegroundApp != packageName && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            Log.d(TAG, "Foreground app definitively changed: $currentForegroundApp -> $packageName (Class: $className)")
            currentForegroundApp = packageName
        } else if (currentForegroundApp == null && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            currentForegroundApp = packageName
            Log.d(TAG, "Initial foreground app set: $currentForegroundApp (Class: $className)")
        }

        // Ignore events from own app or launcher
        if (packageName == applicationContext.packageName || isLauncher(packageName)) {
            if (lastTimerStartedForPackage != null && lastTimerStartedForPackage != applicationContext.packageName) {
                Log.d(TAG, "Our app ($packageName) or launcher is in foreground. Stopping timer if it was running for $lastTimerStartedForPackage.")
                sendTimerCommand(TimerService.ACTION_STOP_TIMER, lastTimerStartedForPackage)
                lastTimerStartedForPackage = null
            }
            clearSystemUiPauseState()
            return
        }
        handleAppInForeground(packageName)
    }

    private fun handleAppInForeground(packageName: String, forceReevaluation: Boolean = false) {
        // Log.d(TAG, "handleAppInForeground: $packageName, LastTimer: $lastTimerStartedForPackage, PausedForUI: $isTimerPausedForSystemUI for $appPackagePausedForSystemUI, Force: $forceReevaluation")
        // Log.i(TAG, "Current lockedAppPackages in handleAppInForeground: $lockedAppPackages") // Good for debugging

        if (isTimerPausedForSystemUI && appPackagePausedForSystemUI == packageName && !forceReevaluation) {
            // This case might be hit if SystemUI was up, then this app came back. Resume is handled above.
            // If forced, we continue to evaluate.
            Log.d(TAG, "handleAppInForeground: App $packageName was paused for SystemUI. Resume likely handled. forceReevaluation: $forceReevaluation")
            // No, the resume logic for SystemUI is before this. If we are here, and it was paused, we should check if it needs resuming.
            // The resume logic for SystemUI in onAccessibilityEvent should handle this.
            // However, as a safeguard for `forceReevaluation`:
            if (isTimerPausedForSystemUI && appPackagePausedForSystemUI == packageName) {
                Log.d(TAG, "handleAppInForeground: App $packageName was paused for SystemUI. Attempting resume if needed.")
                // The actual resume command is sent when SystemUI interaction *ends*.
                // This function is about what to do *now* that this app is foreground.
                // If it's the app that *was* paused, its timer is currently paused.
            }
        }

        if (lockedAppPackages.contains(packageName)) {
            Log.d(TAG, "Locked app in foreground: $packageName")
            val remainingTimeSeconds = timeBankManager.getTimeSeconds()
            Log.d(TAG, "Time available: $remainingTimeSeconds seconds for $packageName")

            if (remainingTimeSeconds <= 0) {
                Log.i(TAG, "Time is zero for locked app $packageName. Launching Interstitial.")
                if (lastTimerStartedForPackage != null) { // Stop any lingering timer
                    sendTimerCommand(TimerService.ACTION_STOP_TIMER, lastTimerStartedForPackage)
                }
                lastTimerStartedForPackage = null
                launchInterstitialBlockActivity(packageName)
            } else {
                if (packageName != lastTimerStartedForPackage || forceReevaluation) {
                    Log.d(TAG, "Time available for $packageName. Ensuring TimerService is running. Force: $forceReevaluation, LastTimer: $lastTimerStartedForPackage")
                    startTimerServiceForLockedApp(packageName)
                } else {
                    Log.d(TAG, "Timer already considered running for $packageName. No action needed.")
                }
            }
        } else {
            Log.d(TAG, "Non-locked app in foreground: $packageName.")
            if (lastTimerStartedForPackage != null) {
                Log.d(TAG, "Stopping timer as non-locked app ($packageName) is foreground. Was running for $lastTimerStartedForPackage.")
                sendTimerCommand(TimerService.ACTION_STOP_TIMER, lastTimerStartedForPackage)
                lastTimerStartedForPackage = null
            }
            clearSystemUiPauseState()
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
            lastTimerStartedForPackage = null
        }
        handleTimeExpirationLogic(expiredAppPackage)
    }

    private fun handleTimeExpirationLogic(expiredAppPackage: String?) {
        if (expiredAppPackage != null &&
            currentForegroundApp == expiredAppPackage &&
            lockedAppPackages.contains(expiredAppPackage)) {
            // Check if our own utility activities are not the ones being blocked
            val isOurOwnUtilityActivity = currentForegroundApp == applicationContext.packageName &&
                    (getForegroundActivityClassNameFromEvent() == PushupActivity::class.java.name ||
                            getForegroundActivityClassNameFromEvent() == MainActivity::class.java.name ||
                            getForegroundActivityClassNameFromEvent() == InterstitialBlockActivity::class.java.name ||
                            classNameIsOurApp(getForegroundActivityClassNameFromEvent())) // A bit redundant if getForegroundActivityClassNameFromEvent is null

            if (!isOurOwnUtilityActivity) { //Simplified, assuming classNameIsOurApp is sufficient if get.. is null
                Log.i(TAG, "$currentForegroundApp (locked) is still foreground after time expired. Re-locking.")
                launchInterstitialBlockActivity(currentForegroundApp)
            } else {
                Log.d(TAG, "Time expired for $currentForegroundApp, but our own activity ($currentForegroundApp) is foreground. No re-lock.")
            }
        } else {
            Log.i(TAG, "Time expired for $expiredAppPackage, but conditions for re-lock not met (current FG: $currentForegroundApp, isLocked: ${lockedAppPackages.contains(expiredAppPackage ?: "")}).")
        }
    }

    // --- Helper Methods ---
    private fun loadLockedAppsList() {
        // Ensure sharedPreferences is initialized
        if (!::sharedPreferences.isInitialized) {
            sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        lockedAppPackages = sharedPreferences.getStringSet(KEY_LOCKED_APPS, emptySet()) ?: emptySet()
        Log.i(TAG, "Locked apps list reloaded: Count = ${lockedAppPackages.size}, Apps = $lockedAppPackages")
    }

    private fun sendTimerCommand(action: String, appPackage: String?) {
        // ... (existing code, ensure it's robust for appPackage being null for stop)
        if (appPackage == null && (action == TimerService.ACTION_PAUSE_TIMER || action == TimerService.ACTION_RESUME_TIMER || action == TimerService.ACTION_START_TIMER)) {
            Log.w(TAG, "Cannot perform $action without an appPackage.")
            return
        }

        Log.i(TAG, "Sending Command to TimerService: $action for App: ${appPackage ?: "N/A"}")
        val serviceIntent = Intent(this, TimerService::class.java).apply {
            this.action = action
            if (appPackage != null) {
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
        // ... (existing code)
        val currentTimeMillis = System.currentTimeMillis()
        if (appPackage == lastTimerStartedForPackage && !isTimerPausedForSystemUI && (currentTimeMillis - lastTimerStartTimeMillis) < timerStartDebounceMillis) {
            Log.d(TAG, "Debouncing START_TIMER for $appPackage (recently started).")
            return
        }

        Log.d(TAG, "Requesting TimerService to START for locked app: $appPackage")
        sendTimerCommand(TimerService.ACTION_START_TIMER, appPackage)
        lastTimerStartedForPackage = appPackage
        if (!isTimerPausedForSystemUI) {
            lastTimerStartTimeMillis = currentTimeMillis
        }
    }

    private fun clearSystemUiPauseState() {
        // ... (existing code)
        if (isTimerPausedForSystemUI) {
            Log.d(TAG, "Clearing SystemUI pause state. Was paused for: $appPackagePausedForSystemUI")
        }
        isTimerPausedForSystemUI = false
        appPackagePausedForSystemUI = null
    }

    private fun launchInterstitialBlockActivity(lockedAppPackage: String?) {
        // ... (existing code)
        if (lockedAppPackage == null) {
            Log.w(TAG, "Attempted to launch InterstitialBlockActivity with null package name.")
            return
        }
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
        // ... (existing code)
        if (packageName == null) return false
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo =
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun classNameIsOurApp(className: String?): Boolean {
        // ... (existing code)
        return className != null && className.startsWith(applicationContext.packageName)
    }

    private fun getForegroundActivityClassNameFromEvent(): String? {
        // ... (existing code, still potentially unreliable)
        Log.w(TAG, "getForegroundActivityClassNameFromEvent: This method is unreliable without a direct event.")
        return null
    }
}