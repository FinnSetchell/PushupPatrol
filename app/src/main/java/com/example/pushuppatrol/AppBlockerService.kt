package com.example.pushuppatrol // Replace with your actual package name


import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    // TODO: Later, this list will be populated from SharedPreferences (Phase 3)
    private val lockedAppPackages = setOf(
        "com.instagram.android",
        "com.google.android.youtube"
    )

    companion object {
        private const val TAG = "AppBlockerService"
    }

    private var currentForegroundApp: String? = null

    // This BroadcastReceiver might become redundant if TimeExpirationListener handles all cases.
    // Evaluate if you still need it after fully implementing the listener pattern.
    private val timeExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "timeExpiredReceiver: onReceive - Action: ${intent.action}")

            if (intent.action == TimerService.BROADCAST_TIME_EXPIRED) {
                val expiredAppPackage = intent.getStringExtra(TimerService.EXTRA_APP_PACKAGE)
                Log.i(TAG, "Time expired broadcast received for app: $expiredAppPackage. Current foreground app: $currentForegroundApp")

                // Check if the current foreground app is one of the locked apps
                if (currentForegroundApp != null && lockedAppPackages.contains(currentForegroundApp)) {
                    val currentActivityName = getForegroundActivityClassName()

                    val isOurOwnAppActivity = currentForegroundApp == applicationContext.packageName &&
                            (currentActivityName == PushupActivity::class.java.name || currentActivityName == MainActivity::class.java.name)

                    if (!isOurOwnAppActivity) {
                        Log.i(TAG, "$currentForegroundApp is a locked app and is still in the foreground. Re-locking.")
                        launchPushupActivity(currentForegroundApp)
                    } else {
                        Log.d(TAG, "Time expired, but our own app activity ($currentActivityName) is foreground. No re-lock.")
                    }
                } else {
                    Log.i(TAG, "Time expired, but a locked app ($expiredAppPackage or other) is no longer in the foreground ($currentForegroundApp). No re-lock action taken.")
                }
            }
        }
    }


    override fun onTimeExpired(expiredAppPackage: String?) {
        Log.i(TAG, "onTimeExpired (via Listener) for app: $expiredAppPackage. Current foreground app: $currentForegroundApp")

        if (currentForegroundApp != null && lockedAppPackages.contains(currentForegroundApp)) {
            if (expiredAppPackage == currentForegroundApp) {
                val currentActivityName = getForegroundActivityClassName()
                val isOurOwnAppActivity = currentForegroundApp == applicationContext.packageName &&
                        (currentActivityName == PushupActivity::class.java.name || currentActivityName == MainActivity::class.java.name)

                if (!isOurOwnAppActivity) {
                    Log.i(TAG, "$currentForegroundApp is a locked app and is still in the foreground. Re-locking.")
                    launchPushupActivity(currentForegroundApp)
                } else {
                    Log.d(TAG, "Time expired, but our own app activity ($currentActivityName) is foreground. No re-lock.")
                }
            } else {
                Log.w(TAG, "Time expired for $expiredAppPackage, but $currentForegroundApp is now in foreground. No re-lock.")
            }
        } else {
            Log.i(TAG, "Time expired, but a locked app ($expiredAppPackage or other) is no longer in the foreground ($currentForegroundApp). No re-lock action taken.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        timeBankManager = TimeBankManager(applicationContext)
        Log.d(TAG, "AppBlockerService created.")
        AppBlockerEventManager.setTimeExpirationListener(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val packageName = event.packageName?.toString()
            val className = event.className?.toString()

            Log.d(TAG, "Event: type=${AccessibilityEvent.eventTypeToString(event.eventType)}, pkg=$packageName, class=$className")

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName != null) {
                currentForegroundApp = packageName
                Log.d(TAG, "Current foreground app updated to: $currentForegroundApp (class: $className)")
            }

            if (packageName != null) {
                val isOurOwnPushupActivity = packageName == applicationContext.packageName && className == PushupActivity::class.java.name
                val isOurOwnMainActivity = packageName == applicationContext.packageName && className == MainActivity::class.java.name

                // Scenario 1: A locked app is in the foreground
                if (packageName in lockedAppPackages && !isOurOwnPushupActivity && !isOurOwnMainActivity) {
                    val remainingTimeSeconds = timeBankManager.getTimeSeconds()
                    Log.d(TAG, "Locked app detected: $packageName. Time remaining: $remainingTimeSeconds seconds.")

                    if (remainingTimeSeconds <= 0) {
                        Log.i(TAG, "Time is up for $packageName. Launching PushupActivity.")
                        val stopIntent = Intent(this, TimerService::class.java).apply {
                            action = TimerService.ACTION_STOP_TIMER
                        }
                        ContextCompat.startForegroundService(this, stopIntent)
                        launchPushupActivity(packageName)
                    } else {
                        // Time is available. Start the TimerService if it's not already running for this app.
                        val currentTimeMillis = System.currentTimeMillis()
                        if (packageName == lastStartedAppPackage && (currentTimeMillis - lastStartTimeMillis) < startDebounceMillis) {
                            Log.d(TAG, "Debouncing ACTION_START_TIMER for $packageName, recently started.")
                        } else {
                            Log.d(TAG, "$packageName is a locked app, time available. Ensuring TimerService is running.")
                            startTimerService(packageName)
                            lastStartedAppPackage = packageName
                            lastStartTimeMillis = currentTimeMillis
                        }
                    }
                }
                // Scenario 2: A non-locked app is in the foreground, or our app's UI (not PushupActivity)
                else if (packageName != applicationContext.packageName || isOurOwnMainActivity) {
                    // The condition `packageName != applicationContext.packageName` means it's another app (non-locked)
                    // `isOurOwnMainActivity` means our main UI is front.
                    Log.d(TAG, "Non-locked app ($packageName) or our own Main UI detected. Stopping TimerService.")
                    val stopIntent = Intent(this, TimerService::class.java).apply {
                        action = TimerService.ACTION_STOP_TIMER
                    }
                    ContextCompat.startForegroundService(this, stopIntent)
                    lastStartedAppPackage = null // Clear last started app
                }
                // Scenario 3: Our PushupActivity is in the foreground
                else if (isOurOwnPushupActivity) {
                    Log.d(TAG, "PushupActivity is foreground. Timer should have been stopped or not started. No action for TimerService here.")
                    // TimerService should ideally be stopped *before* PushupActivity is launched.
                    // If it's already foreground, we assume TimerService is not (or should not be) running for a locked app.
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppBlockerService interrupted.")
        AppBlockerEventManager.setTimeExpirationListener(null) // Unregister
    }

    override fun onDestroy() {
        AppBlockerEventManager.setTimeExpirationListener(null) // Unregister
        Log.w(TAG, "AppBlockerService destroyed.")
        super.onDestroy()
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
        Log.i(TAG, "AppBlockerService connected and configured.")
        Toast.makeText(this, "Push-up Patrol Blocker Active", Toast.LENGTH_SHORT).show()
    }

    private fun startTimerService(appPackage: String) {
        Log.d(TAG, "$appPackage is a locked app, time available. Ensuring TimerService is running.")
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
                putExtra(PushupActivity.EXTRA_BLOCKED_APP_NAME, lockedAppPackage)
            }
        }
        startActivity(intent)
    }

    private fun getForegroundActivityClassName(): String? {
        // This implementation remains a challenge from an AccessibilityService
        // without querying window manager or using other more complex methods.
        // For the purpose of the onTimeExpired listener, you'll primarily rely on
        // currentForegroundApp (package name) to decide if re-locking is needed.
        return null
    }
}