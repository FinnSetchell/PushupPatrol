package com.example.pushuppatrol

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {

    private lateinit var timeBankManager: TimeBankManager
    private lateinit var timeDisplayTextView: TextView
    private lateinit var enableAccessibilityButton: Button
    private lateinit var resetTimeButton: Button // Declare the new button

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("MainActivity", "POST_NOTIFICATIONS permission granted by user.")
                // You could trigger a re-check or update UI if needed
            } else {
                Log.w("MainActivity", "POST_NOTIFICATIONS permission denied by user.")
                Toast.makeText(
                    this,
                    "Notifications permission denied. Timer updates will not be shown.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted.")
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // TODO: Show an educational UI to the user explaining why you need the permission.
                // For now, we'll just request it directly.
                Log.d("MainActivity", "Showing rationale (or just requesting) for POST_NOTIFICATIONS.")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Directly ask for the permission if it hasn't been requested before or if rationale isn't needed
                Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission.")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        timeBankManager = TimeBankManager(applicationContext)

        val tempLaunchButton: Button = findViewById(R.id.tempLaunchPushupActivityButton)
        tempLaunchButton.setOnClickListener {
            startActivity(Intent(this, PushupActivity::class.java))
        }

        timeDisplayTextView = findViewById(R.id.tempTimeDisplay)
        enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton)
        resetTimeButton = findViewById(R.id.resetTimeButton) // Initialize the new button

        enableAccessibilityButton.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find 'Push-up Patrol Blocker' and enable it.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Accessibility Settings.", Toast.LENGTH_SHORT).show()
                Log.e("MainActivity", "Error opening accessibility settings", e)
            }
        }

        // --- Add OnClickListener for Reset Time Button ---
        resetTimeButton.setOnClickListener {
            timeBankManager.clearTimeBank() // Call the method in TimeBankManager
            updateDisplayedTime() // Update the UI
            Toast.makeText(this, "Time bank reset!", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Time bank cleared by user.")
        }
        // --- End OnClickListener ---

        // Ask for notification permission when MainActivity is created or resumed
        askNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        updateDisplayedTime() // Call helper to update time
        updateAccessibilityButtonState() // Call helper to update accessibility button
    }

    // Helper function to update the displayed time
    private fun updateDisplayedTime() {
        val totalSeconds = timeBankManager.getTimeSeconds()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        timeDisplayTextView.text = "Time Bank: ${minutes}m ${seconds}s"
    }

    // Helper function to update accessibility button state
    private fun updateAccessibilityButtonState() {
        if (isAccessibilityServiceEnabled()) {
            enableAccessibilityButton.text = "Accessibility Service Enabled"
            enableAccessibilityButton.isEnabled = false
        } else {
            enableAccessibilityButton.text = "Enable App Blocker Service"
            enableAccessibilityButton.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        // ... (your existing isAccessibilityServiceEnabled function)
        var accessibilityEnabled = 0
        val serviceId = "${packageName}/${AppBlockerService::class.java.canonicalName}"
        // Log.d("MainActivity", "Checking for service: $serviceId") // Keep this for debugging

        try {
            accessibilityEnabled = Settings.Secure.getInt(
                applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            // Log.e("MainActivity", "Accessibility not found in settings.", e) // Keep this
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                colonSplitter.setString(settingValue)
                while (colonSplitter.hasNext()) {
                    val accessibilityService = colonSplitter.next()
                    if (accessibilityService.equals(serviceId, ignoreCase = true)) {
                        // Log.i("MainActivity", "AppBlockerService is ENABLED") // Keep this
                        return true
                    }
                }
            }
        }
        // Log.w("MainActivity", "AppBlockerService is DISABLED") // Keep this
        return false
    }
}