package com.example.pushuppatrol

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.pushuppatrol.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var timeBankManager: TimeBankManager
    private lateinit var binding: ActivityMainBinding

    // Existing launcher for POST_NOTIFICATIONS (re-used or you can make a new one)
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("MainActivity", "POST_NOTIFICATIONS permission granted by user.")
                // You could potentially trigger something here if needed after permission is granted,
                // like re-checking if a service should start its notification.
                // For now, just logging is fine as the service will attempt to post when it starts.
            } else {
                Log.w("MainActivity", "POST_NOTIFICATIONS permission denied by user.")
                Toast.makeText(
                    this,
                    "Notifications permission denied. Timer updates might not be shown.", // Updated message
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // New launcher specific to the general notification permission for clarity,
    // or you can reuse the one from your askNotificationPermission() method if it's for POST_NOTIFICATIONS.
    // For simplicity, I'll use your existing one if it's for POST_NOTIFICATIONS.
    // Let's assume `askNotificationPermission` and `requestPermissionLauncher` handles POST_NOTIFICATIONS.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        timeBankManager = TimeBankManager(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request Notification permission (for API 33+)
        // This is now handled by askNotificationPermission() which you already have.
        // We just need to ensure it's called.
        askNotificationPermission() // Call your existing method for requesting notification permissions

        binding.btnEarnTime.setOnClickListener {
            startActivity(Intent(this, PushupActivity::class.java))
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            startActivity(intent)
        }

        binding.enableAccessibilityButton.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find 'Push-up Patrol Blocker' and enable it.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Accessibility Settings.", Toast.LENGTH_SHORT).show()
                Log.e("MainActivity", "Error opening accessibility settings", e)
            }
        }

        binding.resetTimeButton.setOnClickListener {
            timeBankManager.clearTimeBank()
            updateDisplayedTime()
            Toast.makeText(this, "Time bank reset!", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Time bank cleared by user.")
        }

        binding.btnDevAdd10Seconds.setOnClickListener {
            timeBankManager.addTimeSeconds(10)
            updateDisplayedTime()
            Toast.makeText(this, "+10 seconds added!", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity_Dev", "Developer added 10 seconds. New total: ${timeBankManager.getTimeSeconds()}s")
        }

        updateDisplayedTime()
        updateAccessibilityButtonState()
        // The notification channel creation is now handled by MainApplication.kt
        // So, no need for createAppNotificationChannels() here.
    }

    // Your existing askNotificationPermission method should handle POST_NOTIFICATIONS
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Check for Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted.")
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                Log.d("MainActivity", "Showing rationale for POST_NOTIFICATIONS.")
                // Here you might want to show a more user-friendly dialog explaining why you need the permission
                // before calling launch again. For now, directly launching.
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission.")
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // For older versions, POST_NOTIFICATIONS permission is granted by default at install time.
            Log.d("MainActivity", "Below Android 13, POST_NOTIFICATIONS permission is not needed at runtime.")
        }
    }


    override fun onResume() {
        super.onResume()
        updateDisplayedTime()
        updateAccessibilityButtonState()
    }

    private fun updateDisplayedTime() {
        val totalSeconds = timeBankManager.getTimeSeconds()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.tvTimeRemaining.text = String.format("%02d:%02d", minutes, seconds) // Formatted time
    }

    private fun updateAccessibilityButtonState() {
        if (isAccessibilityServiceEnabled()) {
            binding.enableAccessibilityButton.text = "Accessibility Service Enabled"
            binding.enableAccessibilityButton.isEnabled = false
        } else {
            binding.enableAccessibilityButton.text = "Enable App Blocker Service"
            binding.enableAccessibilityButton.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        var accessibilityEnabled = 0
        val serviceId = "${packageName}/${AppBlockerService::class.java.canonicalName}"
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            Log.e("MainActivity", "Accessibility setting not found.", e)
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                colonSplitter.setString(settingValue)
                while (colonSplitter.hasNext()) {
                    val accessibilityService = colonSplitter.next()
                    if (accessibilityService.equals(serviceId, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}