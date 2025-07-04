package com.example.pushuppatrol.ui.main

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
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.pushuppatrol.core.blocking.AppBlockerService
import com.example.pushuppatrol.R
import com.example.pushuppatrol.ui.settings.SettingsActivity
import com.example.pushuppatrol.core.time.TimeBankManager
import com.example.pushuppatrol.databinding.ActivityMainBinding
import com.example.pushuppatrol.util.launcher.ActivityLauncher

class MainActivity : AppCompatActivity() {

    private lateinit var timeBankManager: TimeBankManager
    private lateinit var binding: ActivityMainBinding

    // --- Cooldown for Dev Add Time Button ---
    private var lastDevAddTimeToastTimestamp: Long = 0L
    private val devAddTimeToastCooldownMs: Long = 2000L // 2 seconds cooldown
    // --- End Cooldown ---

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

        binding.btnEarnTime.setOnClickListener {
            val activityToLaunch = timeBankManager.getDefaultActivityType()
            Log.d(TAG, "Launching activity of type (from settings): $activityToLaunch")
            ActivityLauncher.launchActivity(this, activityToLaunch)
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(this, AppSelectionActivity::class.java)
            startActivity(intent)
        }

        // Corrected ID based on your previous messages if you renamed it
        // If your ID is still binding.btnEnableAccessibility, use that.
        // Assuming you might have renamed it to something like 'enableAccessibilityButton'
        // based on the provided snippet. If not, use the correct ID from your layout.
        val enableAccessibilityButtonId = try {
            binding.root.findViewById<View>(R.id.btnEnableAccessibility) // Example if ID was changed
        } catch (e: Exception) {
            binding.btnEnableAccessibility // Fallback to an assumed original name if the above fails
        }

        enableAccessibilityButtonId.setOnClickListener {
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


        // Corrected ID based on your previous messages if you renamed it
        // Assuming you might have renamed it to something like 'resetTimeButton'
        // If not, use the correct ID from your layout.
        val resetTimeButtonId = try {
            binding.root.findViewById<View>(R.id.btnResetTime) // Example if ID was changed
        } catch (e: Exception) {
            binding.btnResetTime // Fallback to an assumed original name
        }

        resetTimeButtonId.setOnClickListener {
            timeBankManager.clearTimeBank()
            updateDisplayedTime()
            Toast.makeText(this, "Time bank reset!", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Time bank cleared by user.")
        }

        binding.btnDevAdd10Seconds.setOnClickListener {
            timeBankManager.addTimeSeconds(10)
            updateDisplayedTime()

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastDevAddTimeToastTimestamp > devAddTimeToastCooldownMs) {
                Toast.makeText(this, "+10 seconds added!", Toast.LENGTH_SHORT).show()
                lastDevAddTimeToastTimestamp = currentTime
            }
            Log.d(
                TAG,
                "Developer added 10 seconds. New total: ${timeBankManager.getTimeSeconds()}s"
            )
        }

        updateDisplayedTime()
        updateAccessibilityButtonState()
    }

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
    }

    private fun updateDisplayedTime() {
        val totalSeconds = timeBankManager.getTimeSeconds()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.tvTimeRemaining.text = String.format("%02d:%02d", minutes, seconds)
    }

    private fun updateAccessibilityButtonState() {
        // Use the correct binding ID for the accessibility button here
        // This is a placeholder based on the discussion.
        // Replace 'binding.enableAccessibilityButton' with the actual ID if it's different.
        val accessibilityButton = try {
            // Attempt to use a common renamed ID first
            binding.root.findViewById<android.widget.Button>(R.id.btnEnableAccessibility)
        } catch (e: Exception) {
            // Fallback to what might be the original name in your binding
            // If this is also incorrect, ensure your layout and binding match.
            if (::binding.isInitialized && binding.btnEnableAccessibility is android.widget.Button) {
                binding.btnEnableAccessibility as android.widget.Button
            } else {
                // If neither works, you have a mismatch. For now, we'll just log and not crash.
                // You'll need to fix the ID in your code to match your layout.
                Log.e(TAG, "Accessibility button ID mismatch in updateAccessibilityButtonState.")
                return
            }
        }


        if (isAccessibilityServiceEnabled()) {
            accessibilityButton.text =
                getString(R.string.accessibility_service_enabled_text)
            accessibilityButton.isEnabled = false
        } else {
            accessibilityButton.text =
                getString(R.string.enable_app_blocker_service_text)
            accessibilityButton.isEnabled = true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        var accessibilityEnabled = 0
        // Ensure AppBlockerService::class.java.canonicalName does not return null
        val serviceName = AppBlockerService::class.java.canonicalName ?: return false
        val serviceId = "${packageName}/${serviceName}"
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