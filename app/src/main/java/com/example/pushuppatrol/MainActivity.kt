package com.example.pushuppatrol

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.pushuppatrol.databinding.ActivityMainBinding
import com.example.pushuppatrol.launcher.ActivityLauncher

class MainActivity : AppCompatActivity() {

    private lateinit var timeBankManager: TimeBankManager
    private lateinit var binding: ActivityMainBinding
    // private var selectedActivityType: ActivityType = ActivityType.PUSHUPS // Remove this

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.i(TAG, "POST_NOTIFICATIONS permission granted by user.")
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied by user.")
                Toast.makeText(
                    this,
                    "Notifications permission denied. Timer updates might not be shown.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        timeBankManager = TimeBankManager(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarMain)
        askNotificationPermission()

        // setupActivityTypeSpinner() // Remove this method call

        binding.btnEarnTime.setOnClickListener {
            // Read the saved default activity type from TimeBankManager (SharedPreferences)
            val activityToLaunch = timeBankManager.getDefaultActivityType()
            Log.d(TAG, "Launching activity of type (from settings): $activityToLaunch")
            ActivityLauncher.launchActivity(this, activityToLaunch)
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            startActivity(intent)
        }

        binding.enableAccessibilityButton.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Find 'Push-up Patrol Blocker' and enable it.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Accessibility Settings.", Toast.LENGTH_SHORT)
                    .show()
                Log.e(TAG, "Error opening accessibility settings", e)
            }
        }

        binding.resetTimeButton.setOnClickListener {
            timeBankManager.clearTimeBank()
            // Optionally, also reset the default activity type in TimeBankManager.clearTimeBank() if desired
            // timeBankManager.setDefaultActivityType(ActivityType.PUSHUPS) // Example
            updateDisplayedTime()
            Toast.makeText(this, "Time bank reset!", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Time bank cleared by user.")
        }

        binding.btnDevAdd10Seconds.setOnClickListener {
            timeBankManager.addTimeSeconds(10)
            updateDisplayedTime()
            Toast.makeText(this, "+10 seconds added!", Toast.LENGTH_SHORT).show()
            Log.d(
                TAG,
                "Developer added 10 seconds. New total: ${timeBankManager.getTimeSeconds()}s"
            )
        }

        updateDisplayedTime()
        updateAccessibilityButtonState()
    }

    // private fun setupActivityTypeSpinner() { ... } // Delete this entire method

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "POST_NOTIFICATIONS permission already granted.")
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                Log.d(TAG, "Showing rationale for POST_NOTIFICATIONS.")
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS permission.")
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            Log.d(TAG, "Below Android 13, POST_NOTIFICATIONS permission is not needed at runtime.")
        }
    }

    override fun onResume() {
        super.onResume()
        updateDisplayedTime()
        updateAccessibilityButtonState()
        // No need to update spinner as it's not here
    }

    private fun updateDisplayedTime() {
        val totalSeconds = timeBankManager.getTimeSeconds()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.tvTimeRemaining.text = String.format("%02d:%02d", minutes, seconds)
    }

    private fun updateAccessibilityButtonState() {
        if (isAccessibilityServiceEnabled()) {
            binding.enableAccessibilityButton.text =
                getString(R.string.accessibility_service_enabled_text)
            binding.enableAccessibilityButton.isEnabled = false
        } else {
            binding.enableAccessibilityButton.text =
                getString(R.string.enable_app_blocker_service_text)
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
            Log.e(TAG, "Accessibility setting not found.", e)
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

    companion object {
        private const val TAG = "MainActivity"
    }
}