package com.example.pushuppatrol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.pushuppatrol.activitytracking.ActivityProgressListener
import com.example.pushuppatrol.activitytracking.TrackableActivity
import com.example.pushuppatrol.activitytracking.detectors.PushupDetector
import com.example.pushuppatrol.databinding.ActivityPushupBinding

class PushupActivity : AppCompatActivity(), ActivityProgressListener {

    private lateinit var binding: ActivityPushupBinding
    private var currentPushupCount = 0
    private lateinit var timeBankManager: TimeBankManager
    private var blockedAppPackageNameExtra: String? = null
    private lateinit var pushupDetector: TrackableActivity

    companion object {
        private const val TAG = "PushupActivity"
        // CAMERA_PERMISSION_REQUEST_CODE is no longer needed with ActivityResultLauncher
        const val EXTRA_BLOCKED_APP_NAME = "com.example.pushuppatrol.EXTRA_BLOCKED_APP_NAME"
    }

    // --- Modern Permission Handling ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Check if ALL required permissions were granted
            val requiredPermissionsFromDetector = pushupDetector.getRequiredPermissions()
            val allGranted = requiredPermissionsFromDetector.all { permissions[it] == true }

            if (allGranted) {
                Log.d(TAG, "All permissions granted by user.")
                startPushupTracking()
            } else {
                // Handle the case where some permissions were denied
                // Check which specific permissions were denied, if needed for more granular feedback
                val deniedPermissions = requiredPermissionsFromDetector.filter { permissions[it] == false }
                Log.w(TAG, "Permissions denied by user: $deniedPermissions")
                Toast.makeText(
                    this,
                    "Camera permission is required to count push-ups. Please grant the permission in settings.",
                    Toast.LENGTH_LONG
                ).show()
                // Optionally, guide the user to app settings
                // For now, finish the activity as it cannot function without permissions.
                finish()
            }
        }
    // --- End Modern Permission Handling ---


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPushupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        timeBankManager = TimeBankManager(applicationContext)
        pushupDetector = PushupDetector()

        if (pushupDetector is PushupDetector) {
            (pushupDetector as PushupDetector).previewViewProvider = {
                binding.previewView.surfaceProvider
            }
        }

        blockedAppPackageNameExtra = intent.getStringExtra(EXTRA_BLOCKED_APP_NAME)
        Log.d(TAG, "PushupActivity started. Blocked app package: $blockedAppPackageNameExtra")

        binding.tvPushupCount.text = "$currentPushupCount"

        // Permission handling: Check and request using the new launcher
        checkAndRequestPermissions()

        binding.btnFinish.visibility = View.VISIBLE
        binding.btnFinish.setOnClickListener {
            pushupDetector.stopTracking()

            val actualSecondsPerPushup = timeBankManager.getSecondsPerPushup()
            timeBankManager.addPushups(currentPushupCount, actualSecondsPerPushup)

            val timeEarned = currentPushupCount * actualSecondsPerPushup
            val totalTimeMinutes = timeBankManager.getTimeSeconds() / 60
            val totalTimeRemainingSeconds = timeBankManager.getTimeSeconds() % 60

            Toast.makeText(
                this,
                "$currentPushupCount push-ups done! $timeEarned seconds earned ($actualSecondsPerPushup s/push-up). Total: ${totalTimeMinutes}m ${totalTimeRemainingSeconds}s",
                Toast.LENGTH_LONG
            ).show()
            Log.d(
                TAG,
                "$currentPushupCount push-ups. Earned $timeEarned seconds. Seconds per push-up setting: $actualSecondsPerPushup. New total: ${timeBankManager.getTimeSeconds()}s"
            )
            finish()
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to get app name for $packageName", e)
            packageName
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = pushupDetector.getRequiredPermissions()

        // Check if all required permissions are already granted
        val allPermissionsAlreadyGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsAlreadyGranted) {
            Log.d(TAG, "All required permissions already granted.")
            startPushupTracking()
        } else {
            Log.d(TAG, "Requesting permissions: ${requiredPermissions.joinToString()}")
            // Launch the permission request
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun startPushupTracking() {
        Log.d(TAG, "Permissions granted, starting push-up tracking.")
        pushupDetector.startTracking(this, this, null)
    }

    // `onRequestPermissionsResult` is no longer needed with ActivityResultLauncher
    // override fun onRequestPermissionsResult(...) { ... }

    override fun onBackPressed() {
        pushupDetector.stopTracking()
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        pushupDetector.stopTracking()
        Log.d(TAG, "PushupActivity destroyed.")
    }

    // --- ActivityProgressListener Implementation ---

    override fun onProgressUpdate(count: Int, currentUnitName: String) {
        currentPushupCount = count
        runOnUiThread {
            // Include unit name in the display for clarity, as per your interface design
            binding.tvPushupCount.text = "$count $currentUnitName"
            // Log.d(TAG, "UI Update: $count $currentUnitName")
        }
    }

    override fun onActivityCompleted(earnedUnits: Int, finalUnitName: String) {
        runOnUiThread {
            Log.i(
                TAG,
                "Activity completed: $earnedUnits $finalUnitName (This callback might not be used by current PushupDetector)"
            )
        }
    }

    override fun onError(errorMessage: String, errorDetails: String?) {
        runOnUiThread {
            Log.e(TAG, "Error from PushupDetector: $errorMessage - Details: $errorDetails")
            Toast.makeText(this, "Error: $errorMessage", Toast.LENGTH_LONG).show()
            // Consider more specific error handling, e.g., finish if critical camera error
            // if (errorMessage.contains("Camera", ignoreCase = true)) {
            //     finish()
            // }
        }
    }

    override fun onPermissionMissing(missingPermissions: Array<String>) {
        runOnUiThread {
            Log.w(TAG, "PushupDetector reported missing permissions: ${missingPermissions.joinToString()}")
            Toast.makeText(
                this,
                "Required permissions are missing: ${missingPermissions.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
            // This callback is less likely to be hit now that PushupActivity checks upfront
            // but good for detectors that might re-check or have other permission needs.
            // finish() // Or re-request
        }
    }

    override fun onSetupStateChanged(message: String, isReady: Boolean) {
        runOnUiThread {
            Log.i(TAG, "Setup state changed: $message (Ready: $isReady)")
            // You could have a dedicated status TextView in your layout
            // binding.tvStatus.text = message
            // binding.btnStartManually.isEnabled = isReady // If you had a manual start
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}