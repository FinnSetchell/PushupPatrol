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

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i("MainActivity", "POST_NOTIFICATIONS permission granted by user.")
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("MainActivity", "POST_NOTIFICATIONS permission already granted.")
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Log.d("MainActivity", "Showing rationale for POST_NOTIFICATIONS.")
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission.")
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        timeBankManager = TimeBankManager(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEarnTime.setOnClickListener { // Changed from tempLaunchButton
            startActivity(Intent(this, PushupActivity::class.java))
        }

        binding.btnSelectApps.setOnClickListener { // This was the problematic line
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
            updateDisplayedTime() // This will now use binding.tvTimeRemaining
            Toast.makeText(this, "Time bank reset!", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Time bank cleared by user.")
        }

        binding.btnDevAdd10Seconds.setOnClickListener {
            timeBankManager.addTimeSeconds(10)
            updateDisplayedTime() // This will now use binding.tvTimeRemaining
            Toast.makeText(this, "+10 seconds added!", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity_Dev", "Developer added 10 seconds. New total: ${timeBankManager.getTimeSeconds()}s")
        }

        askNotificationPermission()
        updateDisplayedTime()
        updateAccessibilityButtonState()
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
        // Use binding to access the TextView
        binding.tvTimeRemaining.text = "${minutes}:${seconds}"
    }

    private fun updateAccessibilityButtonState() {
        if (isAccessibilityServiceEnabled()) {
            // Use binding to access the Button
            binding.enableAccessibilityButton.text = "Accessibility Service Enabled"
            binding.enableAccessibilityButton.isEnabled = false
        } else {
            // Use binding to access the Button
            binding.enableAccessibilityButton.text = "Enable App Blocker Service"
            binding.enableAccessibilityButton.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        var accessibilityEnabled = 0
        // It's good practice to use applicationContext when getting system services
        // or content resolver outside of an Activity's direct lifecycle if possible,
        // but here it's within a method called by the activity, so 'this' or 'applicationContext' is fine.
        val serviceId = "${packageName}/${AppBlockerService::class.java.canonicalName}"
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                contentResolver, // Changed from applicationContext.contentResolver for consistency
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            Log.e("MainActivity", "Accessibility setting not found.", e) // Added log
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                contentResolver, // Changed from applicationContext.contentResolver
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