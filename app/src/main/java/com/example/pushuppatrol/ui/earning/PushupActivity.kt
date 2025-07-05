package com.example.pushuppatrol.ui.earning

import android.Manifest // Keep for permission string
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.pushuppatrol.R
import com.example.pushuppatrol.core.time.TimeBankManager
import com.example.pushuppatrol.databinding.ActivityPushupBinding
import com.example.pushuppatrol.features.activitytracking.ActivityProgressListener
import com.example.pushuppatrol.features.activitytracking.TrackableActivity
import com.example.pushuppatrol.features.activitytracking.detectors.PushupDetector
import com.example.pushuppatrol.features.activitytracking.detectors.PoseProcessDebugListener
import com.example.pushuppatrol.features.activitytracking.PoseUpdateListener
import com.google.mlkit.vision.pose.Pose

class PushupActivity : AppCompatActivity(), ActivityProgressListener, PoseProcessDebugListener, PoseUpdateListener {

    private lateinit var binding: ActivityPushupBinding
    private var currentPushupCount = 0 // Updated by ActivityProgressListener
    private lateinit var timeBankManager: TimeBankManager
    // private var blockedAppPackageNameExtra: String? = null // Keep if used for other logic

    private lateinit var activityDetector: TrackableActivity // Use the interface

    companion object {
        private const val TAG = "PushupActivity"
        // EXTRA_BLOCKED_APP_NAME can remain if you use it for other purposes
        // const val EXTRA_BLOCKED_APP_NAME = "com.example.pushuppatrol.EXTRA_BLOCKED_APP_NAME"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val requiredPermissions = activityDetector.getRequiredPermissions() // Get from detector
            val allGranted = requiredPermissions.all { permissions[it] == true }

            if (allGranted) {
                Log.d(TAG, "All permissions granted by user.")
                startActualTracking()
            } else {
                val deniedPermissions = requiredPermissions.filter { permissions[it] == false }
                Log.w(TAG, "Permissions denied by user: $deniedPermissions")
                Toast.makeText(
                    this,
                    "Camera permission is required to count ${activityDetector.getDisplayName(this)}. Please grant the permission in settings.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPushupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        timeBankManager = TimeBankManager(applicationContext)

        // Initialize the specific detector
        activityDetector = PushupDetector()

        // Pass the SurfaceProvider to the detector
        if (activityDetector is PushupDetector) { // Or a more generic way if you have a base class
            (activityDetector as PushupDetector).previewSurfaceProvider = binding.previewView.surfaceProvider
        }

        if (isDebugBuild()) { // Implement isDebugBuild() or use BuildConfig.DEBUG
            binding.tvDebugInfo.visibility = View.VISIBLE
        }

        // blockedAppPackageNameExtra = intent.getStringExtra(EXTRA_BLOCKED_APP_NAME)
        // Log.d(TAG, "PushupActivity started. Blocked app package: $blockedAppPackageNameExtra")

        // Set initial text using detector's unit name
        binding.tvPushupCount.text = "$currentPushupCount ${activityDetector.getUnitName(this)}"
        binding.tvGuidance.text = getString(R.string.tv_guidance_default) // Or activity specific guidance
        binding.tvGuidance.visibility = View.VISIBLE // Show guidance

        checkAndRequestPermissions()

        binding.btnFinish.setOnClickListener {
            activityDetector.stopTracking() // Stop the detector

            val actualSecondsPerPushup = timeBankManager.getSecondsPerPushup()
            timeBankManager.addPushups(currentPushupCount, actualSecondsPerPushup)

            val timeEarned = currentPushupCount * actualSecondsPerPushup
            val totalTimeMinutes = timeBankManager.getTimeSeconds() / 60
            val totalTimeRemainingSeconds = timeBankManager.getTimeSeconds() % 60

            Toast.makeText(
                this,
                "$currentPushupCount ${activityDetector.getUnitName(this)} done! $timeEarned seconds earned. Total: ${totalTimeMinutes}m ${totalTimeRemainingSeconds}s",
                Toast.LENGTH_LONG
            ).show()
            Log.d(
                TAG,
                "$currentPushupCount ${activityDetector.getUnitName(this)}. Earned $timeEarned seconds. New total: ${timeBankManager.getTimeSeconds()}s"
            )
            finish()
        }
    }

    private fun isDebugBuild(): Boolean {
        return 0 != (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = activityDetector.getRequiredPermissions()
        val allPermissionsAlreadyGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsAlreadyGranted) {
            Log.d(TAG, "All required permissions already granted for ${activityDetector.getDisplayName(this)}.")
            startActualTracking()
        } else {
            Log.d(TAG, "Requesting permissions for ${activityDetector.getDisplayName(this)}: ${requiredPermissions.joinToString()}")
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun startActualTracking() {
        Log.d(TAG, "Permissions granted, starting tracking for ${activityDetector.getDisplayName(this)}.")
        // The Activity itself is the LifecycleOwner and the ActivityProgressListener
        activityDetector.startTracking(this, this, null) // Pass `this` as context (LifecycleOwner)
        binding.tvGuidance.visibility = View.GONE // Hide guidance once tracking starts
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called, stopping tracking.")
        activityDetector.stopTracking() // Ensure tracking stops if activity is paused
        binding.poseOverlayView.clear()
    }

    override fun onPoseDetected(pose: Pose, imageWidth: Int, imageHeight: Int, isFrontCamera: Boolean) {
        runOnUiThread {
            binding.poseOverlayView.updatePose(pose, imageWidth, imageHeight, isFrontCamera)
        }
    }

    override fun onClearPose() {
        runOnUiThread {
            binding.poseOverlayView.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called, ensuring tracking is stopped.")
        // activityDetector.stopTracking() // stopTracking should be robust enough to be called multiple times
        // or if already stopped. Typically called in onPause or onStop.
        // If not already called by onPause, this is a final safeguard.
        // The detector's stopTracking should handle its own state.
    }

    // --- ActivityProgressListener Implementation ---

    override fun onProgressUpdate(count: Int, currentUnitName: String) {
        currentPushupCount = count
        runOnUiThread {
            binding.tvPushupCount.text = "$count $currentUnitName"
            // Log.d(TAG, "UI Update: $count $currentUnitName")
        }
    }

    override fun onActivityCompleted(earnedUnits: Int, finalUnitName: String) {
        runOnUiThread {
            Log.i(TAG, "Activity completed by detector: $earnedUnits $finalUnitName")
            // You might use this if the detector itself determines completion (e.g., target reps reached)
        }
    }

    override fun onError(errorMessage: String, errorDetails: String?) {
        runOnUiThread {
            Log.e(TAG, "Error from ${activityDetector.getDisplayName(this)}: $errorMessage - Details: $errorDetails")
            Toast.makeText(this, "Error: $errorMessage", Toast.LENGTH_LONG).show()
            // Potentially finish if it's a critical error like camera failing to start
            if (errorMessage.contains("Camera", ignoreCase = true) || errorMessage.contains("permission", ignoreCase = true)) {
                binding.tvGuidance.text = "Error: $errorMessage. Please restart."
                binding.tvGuidance.visibility = View.VISIBLE
                binding.btnFinish.isEnabled = false // Disable finish if tracking can't even start
            }
        }
    }

    override fun onPermissionMissing(missingPermissions: Array<String>) {
        runOnUiThread {
            // This is less likely to be called directly if Activity checks permissions upfront
            // but the detector might call it if it does its own internal checks or needs new permissions dynamically.
            Log.w(TAG, "${activityDetector.getDisplayName(this)} reported missing permissions: ${missingPermissions.joinToString()}")
            Toast.makeText(this, "Permissions issue: ${missingPermissions.joinToString()}", Toast.LENGTH_LONG).show()
            finish() // Likely need to finish if critical permissions are missing post-start
        }
    }

    override fun onSetupStateChanged(message: String, isReady: Boolean) {
        runOnUiThread {
            Log.i(TAG, "Setup state from ${activityDetector.getDisplayName(this)}: $message (Ready: $isReady)")
            val guidanceText: String
            if (!isReady && message.contains("failed", ignoreCase = true)) {
                guidanceText = message
            } else if (isReady) {
                guidanceText = "Ready! Start your ${activityDetector.getDisplayName(this).lowercase()}."
            } else {
                guidanceText = message
            }
            binding.tvGuidance.text = guidanceText
            binding.tvGuidance.visibility = View.VISIBLE
            // Toast.makeText(this, message, Toast.LENGTH_SHORT).show() // Can be noisy

            // Update debug view as well
            if (binding.tvDebugInfo.visibility == View.VISIBLE) {
                binding.tvDebugInfo.text = "Setup: $message\nReady: $isReady\n${binding.tvDebugInfo.text.toString().substringAfter("\n\n")}" // Prepend setup state
            }
        }
    }

    // --- PoseProcessDebugListener Implementation ---
    override fun onPoseDebugInfo(debugText: String) {
        runOnUiThread {
            if (binding.tvDebugInfo.visibility == View.VISIBLE) {
                // Keep the last few lines of debug info to prevent the TextView from growing indefinitely
                // Or just replace the text entirely each time for simplicity during active debugging.
                val existingText = binding.tvDebugInfo.text.toString()
                val newHeaderText = "Push-ups: $currentPushupCount | State: ${ (activityDetector as? PushupDetector)?.let { /* Access internal state if needed, or get from debugText */ } ?: "N/A"}\n-- Frame Data --\n"
                var fullDebugText = newHeaderText + debugText

                // Basic log scrolling: keep last N lines
                val lines = fullDebugText.split("\n")
                val maxLines = 15 // Adjust as needed
                if (lines.size > maxLines) {
                    fullDebugText = lines.subList(lines.size - maxLines, lines.size).joinToString("\n")
                }
                binding.tvDebugInfo.text = fullDebugText
            }
        }
    }
}

