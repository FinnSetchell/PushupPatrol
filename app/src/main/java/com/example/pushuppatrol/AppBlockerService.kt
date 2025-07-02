package com.example.pushuppatrol // Replace with your actual package name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlin.io.path.name

class AppBlockerService : AccessibilityService() {

    private lateinit var timeBankManager: TimeBankManager

    // TODO: Later, this list will be populated from SharedPreferences (Phase 3)
    // For now, hardcode package names for testing.
    // To find package names:
    // 1. App Info on device -> shows package name
    // 2. Or use an app like "Package Name Viewer 2.0" from Play Store
    private val lockedAppPackages = setOf(
        "com.instagram.android",
        "com.google.android.youtube"
    )

    companion object {
        private const val TAG = "AppBlockerService"
    }

    override fun onCreate() {
        super.onCreate()
        timeBankManager = TimeBankManager(applicationContext)
        Log.d(TAG, "AppBlockerService created.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // We are interested in events indicating a window change or app launch
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val packageName = event.packageName?.toString()
            val className = event.className?.toString()

            Log.d(TAG, "Event: type=${AccessibilityEvent.eventTypeToString(event.eventType)}, pkg=$packageName, class=$className")
            if (packageName != null && packageName in lockedAppPackages) {
                // Prevent locking our own app or the push-up activity itself
                if (packageName == applicationContext.packageName &&
                    (className == PushupActivity::class.java.name || className == MainActivity::class.java.name)) {
                    Log.d(TAG, "Ignoring own app activities.")
                    return
                }

                // Check if the current foreground app is one of the locked apps
                // A more robust check might involve querying ActivityManager, but this is simpler for now.
                // The event source's package name is usually reliable for TYPE_WINDOW_STATE_CHANGED.

                val remainingTimeSeconds = timeBankManager.getTimeSeconds()
                Log.d(TAG, "App detected: $packageName. Time remaining: $remainingTimeSeconds seconds.")

                if (remainingTimeSeconds <= 0) {
                    Log.i(TAG, "Time is up for $packageName. Launching PushupActivity.")
                    // Launch PushupActivity as a lock screen
                    val intent = Intent(this, PushupActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Optional: Add a flag to indicate it's a lock screen
                        // intent.putExtra("isLockScreen", true)
                    }
                    startActivity(intent)
                } else {
                    // Time is available.
                    // In Micro-Goal 2.2, we will start the TimerService here if it's not already running.
                    Log.d(TAG, "$packageName is a locked app, but time is available ($remainingTimeSeconds s).")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AppBlockerService interrupted.")
        // Handle interruption, perhaps by stopping any ongoing monitoring if necessary.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val serviceInfo = AccessibilityServiceInfo().apply {
            // We want to listen for window state changes to detect app launches.
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            // Optionally, specify packages if you only care about a few (not practical for user selection later)
            // packageNames = arrayOf("com.example.app1", "com.example.app2")
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC // Or FEEDBACK_VISUAL, etc.
            // How quickly to receive events.
            notificationTimeout = 100 // Milliseconds
            // Flags for more information
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        setServiceInfo(serviceInfo)
        Log.i(TAG, "AppBlockerService connected and configured.")
        Toast.makeText(this, "Push-up Patrol Blocker Active", Toast.LENGTH_SHORT).show()
    }
}