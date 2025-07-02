package com.example.pushuppatrol

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
    private lateinit var resetTimeButton: Button
    private lateinit var devAdd10SecondsButton: Button

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
        resetTimeButton = findViewById(R.id.resetTimeButton)
        // +++ INITIALIZE THE NEW DEV BUTTON +++
        devAdd10SecondsButton = findViewById(R.id.btnDevAdd10Seconds)


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

        resetTimeButton.setOnClickListener {
            timeBankManager.clearTimeBank()
            updateDisplayedTime()
            Toast.makeText(this, "Time bank reset!", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Time bank cleared by user.")
        }

        devAdd10SecondsButton.setOnClickListener {

            timeBankManager.addTimeSeconds(10)

            updateDisplayedTime()
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
        timeDisplayTextView.text = "Time Bank: ${minutes}m ${seconds}s"
    }

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
        var accessibilityEnabled = 0
        val serviceId = "${packageName}/${AppBlockerService::class.java.canonicalName}"
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
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
                        return true
                    }
                }
            }
        }
        return false
    }
}