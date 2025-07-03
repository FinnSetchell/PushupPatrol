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
    private var lastStartedAppPackage: String? = null
    private var lastStartTimeMillis: Long = 0
    private val startDebounceMillis = 500 // 0.5 seconds debounce window

    // This will now be loaded from SharedPreferences
    private var lockedAppPackages: Set<String> = emptySet() // Changed from val to var, and initialized empty
    private lateinit var sharedPreferences: SharedPreferences // Added

    companion object {
        private const val TAG = "AppBlockerService"
        // These MUST match the constants in AppSelectionActivity
        const val PREFS_NAME = "AppBlockerPrefs" // Use AppSelectionActivity.PREFS_NAME if it's public there
        const val KEY_LOCKED_APPS = "locked_app_packages" // Use AppSelectionActivity.KEY_LOCKED_APPS if public
        const val ACTION_LOCKED_APPS_UPDATED = "com.example.pushuppatrol.LOCKED_APPS_UPDATED" // Added
    }

    private var currentForegroundApp: String? = null

    // BroadcastReceiver for when the timer expires
    private val timeExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "timeExpiredReceiver: onReceive - Action: ${intent.action}")
            if (intent.action == TimerService.BROADCAST_TIME_EXPIRED) {
                val expiredAppPackage = intent.getStringExtra(TimerService.EXTRA_APP_PACKAGE)
                Log.i(TAG, "Time expired broadcast received for app: $expiredAppPackage. Current foreground app: $currentForegroundApp")
                handleTimeExpirationLogic(expiredAppPackage)
            }
        }
    }

    // BroadcastReceiver for when the list of locked apps is updated by AppSelectionActivity
    private val lockedAppsUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_LOCKED_APPS_UPDATED) {
                Log.d(TAG, "Received LOCKED_APPS_UPDATED broadcast. Reloading list.")
                loadLockedAppsList()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AppBlockerService: onCreate")
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) // Initialize SharedPreferences
        timeBankManager = TimeBankManager(applicationContext)
        loadLockedAppsList() // Load the list initially
        Log.d(TAG, "AppBlockerService created. Monitoring apps: $lockedAppPackages")

        AppBlockerEventManager.setTimeExpirationListener(this)

        // Register the locked apps update receiver
        val updateFilter = IntentFilter(ACTION_LOCKED_APPS_UPDATED)
        registerReceiver(lockedAppsUpdateReceiver, updateFilter, Context.RECEIVER_NOT_EXPORTED)


        // Register time expired receiver (if you decide to keep it alongside TimeExpirationListener)
        // Consider if this is still needed if TimeExpirationListener covers all cases.
        // For now, let's assume it might still be useful or a fallback.
        val timeExpiredFilter = IntentFilter(TimerService.BROADCAST_TIME_EXPIRED)
        registerReceiver(timeExpiredReceiver, timeExpiredFilter, Context.RECEIVER_NOT_EXPORTED)

        Log.d(TAG, "Receivers registered.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            // Consider setting packageNames here if the list doesn't change frequently
            // and you want to optimize. However, with dynamic list, it's simpler to filter manually.
        }
        setServiceInfo(serviceInfo)
        Log.i(TAG, "AppBlockerService connected and configured.")
        // Reload in case the list changed while the service was connected but not fully configured
        loadLockedAppsList()
        Toast.makeText(this, "Push-up Patrol Blocker Active", Toast.LENGTH_SHORT).show()
    }

    private fun loadLockedAppsList() {
        lockedAppPackages = sharedPreferences.getStringSet(KEY_LOCKED_APPS, emptySet()) ?: emptySet()
        Log.i(TAG, "Locked apps list reloaded: $lockedAppPackages")
    }

    // From your original TimeExpirationListener
    override fun onTimeExpired(expiredAppPackage: String?) {
        Log.i(TAG, "onTimeExpired (via Listener) for app: $expiredAppPackage. Current foreground app: $currentForegroundApp")
        handleTimeExpirationLogic(expiredAppPackage)
    }

    // Centralized logic for handling time expiration from either source
    private fun handleTimeExpirationLogic(expiredAppPackage: String?) {
        if (currentForegroundApp != null && lockedAppPackages.contains(currentForegroundApp)) {
            // Check if the expired app is the one currently in foreground
            if (expiredAppPackage == currentForegroundApp) {
                val currentActivityName = getForegroundActivityClassName() // This might still return null
                val isOurOwnPushupOrMain = currentForegroundApp == applicationContext.packageName &&
                        (currentActivityName == PushupActivity::class.java.name ||
                                currentActivityName == MainActivity::class.java.name ||
                                classNameIsOurApp(currentActivityName)) // Broader check for our own app

                if (!isOurOwnPushupOrMain) {
                    Log.i(TAG, "$currentForegroundApp is a locked app and is still in the foreground after time expired. Re-locking.")
                    launchPushupActivity(currentForegroundApp)
                } else {
                    Log.d(TAG, "Time expired for $currentForegroundApp, but our own app activity ($currentActivityName) is foreground. No re-lock.")
                }
            } else {
                // Time expired for an app, but a different app is now in the foreground.
                // If this *different* app is also a locked app, it should have its own timer or be blocked.
                // This scenario means the user switched away before the timer for expiredAppPackage visually hit zero
                // or the broadcast was delayed.
                Log.w(TAG, "Time expired for $expiredAppPackage, but $currentForegroundApp is now in foreground. No re-lock for $expiredAppPackage. $currentForegroundApp will be handled by onAccessibilityEvent.")
            }
        } else {
            Log.i(TAG, "Time expired for $expiredAppPackage, but a locked app is no longer in the foreground ($currentForegroundApp). No re-lock action taken for $expiredAppPackage.")
        }
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val packageName = event.packageName?.toString()
            val className = event.className?.toString() // className can be null

            // Log.d(TAG, "Event: type=${AccessibilityEvent.eventTypeToString(event.eventType)}, pkg=$packageName, class=$className")

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName != null) {
                if (currentForegroundApp != packageName) {
                    Log.d(TAG, "Foreground app changed from $currentForegroundApp to $packageName (class: $className)")
                }
                currentForegroundApp = packageName
            }

            if (packageName != null) {
                // Avoid blocking our own app's activities or the launcher
                if (packageName == applicationContext.packageName || isLauncher(packageName)) {
                    // If our app is in foreground (but not PushupActivity), stop the timer for other apps.
                    if (packageName == applicationContext.packageName && className != PushupActivity::class.java.name) {
                        //Log.d(TAG, "Our own app ($className) is in foreground (not PushupActivity). Stopping timer.")
                        //stopTimerServiceIfNeeded() // More generic stop
                    }
                    // Log.d(TAG, "Event for our own app or launcher ($packageName). No blocking action.")
                    return // No further processing for our app or launcher
                }


                // Scenario 1: A locked app is in the foreground
                if (lockedAppPackages.contains(packageName)) {
                    val remainingTimeSeconds = timeBankManager.getTimeSeconds()
                    Log.d(TAG, "Locked app detected: $packageName. Time remaining: $remainingTimeSeconds seconds.")

                    if (remainingTimeSeconds <= 0) {
                        Log.i(TAG, "Time is up for $packageName. Launching PushupActivity.")
                        stopTimerServiceIfNeeded() // Stop timer before launching pushup activity
                        launchPushupActivity(packageName)
                    } else {
                        // Time is available. Start the TimerService if it's not already running for this app.
                        val currentTimeMillis = System.currentTimeMillis()
                        if (packageName == lastStartedAppPackage && (currentTimeMillis - lastStartTimeMillis) < startDebounceMillis) {
                            // Log.d(TAG, "Debouncing ACTION_START_TIMER for $packageName, recently started.")
                        } else {
                            Log.d(TAG, "$packageName is a locked app, time available. Ensuring TimerService is running.")
                            startTimerService(packageName)
                            lastStartedAppPackage = packageName
                            lastStartTimeMillis = currentTimeMillis
                        }
                    }
                }
                // Scenario 2: A non-locked app is in the foreground
                else {
                    // This app is not in our lockedAppPackages list.
                    Log.d(TAG, "Non-locked app ($packageName) in foreground. Stopping timer.")
                    stopTimerServiceIfNeeded()
                    lastStartedAppPackage = null // Clear last started app
                }
            }
        }
    }

    // Helper to check if a class name belongs to our app
    private fun classNameIsOurApp(className: String?): Boolean {
        return className != null && className.startsWith(applicationContext.packageName)
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

    private fun stopTimerServiceIfNeeded() {
        // Only stop if a timer was potentially running for a locked app
        // This check helps prevent unnecessary calls if no locked app was active
        if (lastStartedAppPackage != null) {
            Log.d(TAG, "Stopping TimerService (was potentially running for $lastStartedAppPackage).")
            val stopIntent = Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_STOP_TIMER
            }
            ContextCompat.startForegroundService(this, stopIntent)
            lastStartedAppPackage = null // Clear the record of which app's timer was running
        }
    }


    override fun onInterrupt() {
        Log.w(TAG, "AppBlockerService interrupted.")
        AppBlockerEventManager.setTimeExpirationListener(null) // Unregister if AppBlockerEventManager exists
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "AppBlockerService destroyed.")
        AppBlockerEventManager.setTimeExpirationListener(null) // Unregister if AppBlockerEventManager exists
        unregisterReceiver(lockedAppsUpdateReceiver)
        unregisterReceiver(timeExpiredReceiver) // Also unregister this one
        Log.d(TAG, "Receivers unregistered.")
    }

    private fun startTimerService(appPackage: String) {
        // Log.d(TAG, "$appPackage is a locked app, time available. Ensuring TimerService is running.")
        val serviceIntent = Intent(this, TimerService::class.java).apply {
            action = TimerService.ACTION_START_TIMER
            putExtra(TimerService.EXTRA_APP_PACKAGE, appPackage)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun launchPushupActivity(lockedAppPackage: String?) {
        Log.i(TAG, "Launching PushupActivity for $lockedAppPackage.")
        val intent = Intent(this, PushupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (lockedAppPackage != null) {
                // Ensure this key matches what PushupActivity expects
                putExtra(PushupActivity.EXTRA_BLOCKED_APP_NAME, lockedAppPackage)
            }
        }
        startActivity(intent)
    }

    private fun getForegroundActivityClassName(): String? {
        // This is inherently difficult and unreliable from an AccessibilityService
        // especially across different Android versions and OEM customizations.
        // The event.className is the best source when available.
        // For your logic, primarily rely on event.packageName and currentForegroundApp.
        return null
    }
}