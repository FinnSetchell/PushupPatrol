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

class AppBlockerService : AccessibilityService(), TimeExpirationListener {

    private lateinit var timeBankManager: TimeBankManager
    private lateinit var gracePeriodManager: GracePeriodManager
    private lateinit var sharedPreferences: SharedPreferences // For loading locked app list

    private var lockedAppPackages: Set<String> = emptySet()
    private var currentForegroundApp: String? = null

    // To prevent rapid re-starting of TimerService for the same app
    private var lastTimerStartedForPackage: String? = null
    private var lastTimerStartTimeMillis: Long = 0
    private val timerStartDebounceMillis = 500 // 0.5 seconds

    companion object {
        private const val TAG = "AppBlockerService"

        // Constants for SharedPreferences and Broadcasts (ensure they match AppSelectionActivity)
        // It's good practice to have these in a shared constants file or object.
        const val PREFS_NAME = "AppBlockerPrefs" // Or AppSelectionActivity.PREFS_NAME
        const val KEY_LOCKED_APPS = "locked_app_packages" // Or AppSelectionActivity.KEY_LOCKED_APPS
        const val ACTION_LOCKED_APPS_UPDATED = "com.example.pushuppatrol.LOCKED_APPS_UPDATED"
    }

    // --- Broadcast Receivers ---
    private val timeExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TimerService.BROADCAST_TIME_EXPIRED) {
                val expiredAppPackage = intent.getStringExtra(TimerService.EXTRA_APP_PACKAGE)
                Log.i(TAG, "Time expired broadcast received for app: $expiredAppPackage.")
                handleTimeExpirationLogic(expiredAppPackage)
            }
        }
    }

    private val lockedAppsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_LOCKED_APPS_UPDATED) {
                Log.d(TAG, "LOCKED_APPS_UPDATED broadcast received. Reloading list.")
                loadLockedAppsList()
            }
        }
    }

    // --- Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service is being created.")
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        timeBankManager = TimeBankManager(applicationContext)

        loadLockedAppsList()
        Log.d(TAG, "Initial locked apps: $lockedAppPackages")

        AppBlockerEventManager.setTimeExpirationListener(this)

        // Register broadcast receivers
        registerReceiver(lockedAppsUpdateReceiver, IntentFilter(ACTION_LOCKED_APPS_UPDATED), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(timeExpiredReceiver, IntentFilter(TimerService.BROADCAST_TIME_EXPIRED), Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "Broadcast receivers registered.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100 // How long to wait for events before considering them consecutive.
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            // packageNames can be set here if the list is static, but dynamic loading is fine.
        }
        setServiceInfo(serviceInfo)
        Log.i(TAG, "onServiceConnected: Service connected and configured.")
        loadLockedAppsList() // Reload in case list changed while service was off
        Toast.makeText(this, "Push-up Patrol Blocker Active", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt: Service interrupted.")
        AppBlockerEventManager.setTimeExpirationListener(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "onDestroy: Service is being destroyed.")
        AppBlockerEventManager.setTimeExpirationListener(null)
        unregisterReceiver(lockedAppsUpdateReceiver)
        unregisterReceiver(timeExpiredReceiver)
        Log.d(TAG, "Broadcast receivers unregistered.")
    }

    // --- Core Logic ---
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val packageName = event.packageName?.toString() ?: return // Ignore if no package name
            val className = event.className?.toString()

            // Update current foreground app
            if (currentForegroundApp != packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                Log.d(TAG, "Foreground app changed: $currentForegroundApp -> $packageName (Class: $className)")
                currentForegroundApp = packageName
            } else if (currentForegroundApp == null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                currentForegroundApp = packageName // Initial foreground app
            }


            // Ignore events from own app or launcher to prevent self-blocking loops
            if (packageName == applicationContext.packageName || isLauncher(packageName)) {
                // If it's our own app (but not PushupActivity or Interstitial), ensure any active timer for other apps is stopped.
                if (packageName == applicationContext.packageName &&
                    className != PushupActivity::class.java.name /* && className != InterstitialBlockActivity::class.java.name */) { // Add Interstitial when created
                    // Consider stopping timer only if the previously timed app is different from our app
                    if (lastTimerStartedForPackage != null && lastTimerStartedForPackage != applicationContext.packageName) {
                        Log.d(TAG, "Our app ($className) is in foreground. Stopping timer if it was running for $lastTimerStartedForPackage.")
                        stopTimerServiceForActiveApp()
                    }
                }
                return
            }

            // Handle the foreground app
            handleAppInForeground(packageName)
        }
    }

    private fun handleAppInForeground(packageName: String) {
        if (lockedAppPackages.contains(packageName)) {
            // --- This is a LOCKED APP ---
            Log.d(TAG, "Locked app detected in foreground: $packageName")

            // Check Time Bank
            val remainingTimeSeconds = timeBankManager.getTimeSeconds()
            Log.d(TAG, "Time available: $remainingTimeSeconds seconds for $packageName")

            if (remainingTimeSeconds <= 0) {
                // Time is up. Launch the Interstitial Blocker.
                Log.i(TAG, "Time is zero for $packageName. Launching InterstitialBlockActivity.")
                stopTimerServiceForActiveApp() // Ensure timer is stopped before blocking
                launchInterstitialBlockActivity(packageName) // MODIFIED HERE
            } else {
                // Time available. Start/ensure TimerService is running for this app.
                Log.d(TAG, "Time available for $packageName. Ensuring TimerService is running.")
                startTimerServiceIfNeeded(packageName)
            }
        } else {
            // --- This is a NON-LOCKED APP ---
            Log.d(TAG, "Non-locked app in foreground: $packageName. Stopping timer if it was for a locked app.")
            stopTimerServiceForActiveApp() // Stop timer if a non-locked app comes to foreground
        }
    }


    // --- TimeExpirationListener Implementation ---
    override fun onTimeExpired(expiredAppPackage: String?) {
        Log.i(TAG, "onTimeExpired (Listener): App: $expiredAppPackage. Current FG: $currentForegroundApp")
        handleTimeExpirationLogic(expiredAppPackage)
    }

    private fun handleTimeExpirationLogic(expiredAppPackage: String?) {
        if (currentForegroundApp != null &&
            lockedAppPackages.contains(currentForegroundApp!!) &&
            expiredAppPackage == currentForegroundApp) {

            val currentActivityName = getForegroundActivityClassName()
            val isOurOwnUtilityActivity = currentForegroundApp == applicationContext.packageName &&
                    (currentActivityName == PushupActivity::class.java.name ||
                            currentActivityName == MainActivity::class.java.name ||
                            currentActivityName == InterstitialBlockActivity::class.java.name || // Add Interstitial
                            classNameIsOurApp(currentActivityName))

            if (!isOurOwnUtilityActivity) {
                Log.i(TAG, "$currentForegroundApp (locked) is still foreground after time expired. Re-locking.")
                launchInterstitialBlockActivity(currentForegroundApp) // MODIFIED HERE
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

    private fun startTimerServiceIfNeeded(appPackage: String) {
        val currentTimeMillis = System.currentTimeMillis()
        // Debounce: If TimerService was just started for this app, don't restart it immediately
        if (appPackage == lastTimerStartedForPackage && (currentTimeMillis - lastTimerStartTimeMillis) < timerStartDebounceMillis) {
            Log.d(TAG, "Debouncing startTimerService for $appPackage (recently started).")
            return
        }

        Log.d(TAG, "Starting TimerService for $appPackage.")
        val serviceIntent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_START_TIMER
            putExtra(TimerService.EXTRA_APP_PACKAGE, appPackage)
            // TimerService will get duration from TimeBankManager itself now
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        lastTimerStartedForPackage = appPackage
        lastTimerStartTimeMillis = currentTimeMillis
    }

    private fun stopTimerServiceForActiveApp() {
        // Only stop if a timer was potentially running
        if (lastTimerStartedForPackage != null) {
            Log.d(TAG, "Stopping TimerService (was running for $lastTimerStartedForPackage).")
            val stopIntent = Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_STOP_TIMER
                // Optionally pass the package name if TimerService needs to know which timer instance to stop
                // putExtra(TimerService.EXTRA_APP_PACKAGE, lastTimerStartedForPackage)
            }
            ContextCompat.startForegroundService(this, stopIntent) // Or just stopService(stopIntent) if not always FG
            lastTimerStartedForPackage = null // Clear the record
        }
    }

    // TODO: In Sub-Goal 4.1.2, create this method
    // private fun launchInterstitialBlockActivity(lockedAppPackage: String?) {
    //     Log.i(TAG, "Launching InterstitialBlockActivity for $lockedAppPackage.")
    //     val intent = Intent(this, InterstitialBlockActivity::class.java).apply {
    //         addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    //         putExtra(InterstitialBlockActivity.EXTRA_BLOCKED_APP_PACKAGE, lockedAppPackage)
    //     }
    //     startActivity(intent)
    // }

    private fun launchInterstitialBlockActivity(lockedAppPackage: String?) {
        if (lockedAppPackage == null) {
            Log.w(TAG, "Attempted to launch InterstitialBlockActivity with null package name.")
            return
        }
        Log.i(TAG, "Launching InterstitialBlockActivity for $lockedAppPackage.")
        val intent = Intent(this, InterstitialBlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(InterstitialBlockActivity.EXTRA_BLOCKED_APP_PACKAGE_NAME, lockedAppPackage)
            // Optionally, get and pass the display name here if easily available,
            // otherwise InterstitialBlockActivity can derive it.
            // val appName = getAppNameFromPackage(lockedAppPackage) // You'd need a helper for this in service
            // putExtra(InterstitialBlockActivity.EXTRA_BLOCKED_APP_DISPLAY_NAME, appName)
        }
        startActivity(intent)
    }

    private fun isLauncher(packageName: String): Boolean {
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
        return className != null && className.startsWith(applicationContext.packageName)
    }

    private fun getForegroundActivityClassName(): String? {
        // This is inherently difficult from an AccessibilityService.
        // The event.className is the best source when available.
        // For current logic, primarily relying on event.packageName.
        // If specific activity checks are needed, it's better if they come directly from event.className
        return null // Returning null as a reliable general method is not feasible.
    }
}