package com.example.pushuppatrol // Replace with your actual package name

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.pushuppatrol.databinding.ActivityPushupBinding // Assuming ViewBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PushupActivity : AppCompatActivity() {

    // Assuming you have ViewBinding setup for this activity
    // If not, your findViewById calls are fine.
    private lateinit var binding: ActivityPushupBinding

    // private lateinit var previewView: PreviewView // Via binding
    // private lateinit var pushupCountText: TextView // Via binding
    // private lateinit var doneButton: Button // Via binding
    // private lateinit var blockedAppInfoText: TextView // Via binding

    private lateinit var cameraExecutor: ExecutorService
    private var pushupCount = 0
    private enum class PushupState {
        UP, DOWN, UNKNOWN
    }
    private var currentPushupState: PushupState = PushupState.UNKNOWN
    private var upReferenceY: Float = -1f
    private val downThresholdFactor = 0.25f // Lower part of the push-up
    private val upThresholdFactor = 0.15f   // Range to consider "up" from the lowest point

    private lateinit var poseDetector: PoseDetector
    private var isProcessingFrame = false

    private lateinit var timeBankManager: TimeBankManager
    private var blockedAppPackageNameExtra: String? = null // Renamed for clarity

    companion object {
        private const val TAG = "PushupActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        // Ensure this matches what AppBlockerService (or InterstitialBlockActivity) uses
        const val EXTRA_BLOCKED_APP_NAME = "com.example.pushuppatrol.EXTRA_BLOCKED_APP_NAME" // This is the package name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Assuming ViewBinding:
        binding = ActivityPushupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // If not using ViewBinding, keep your findViewById calls:
        // previewView = findViewById(R.id.previewView)
        // pushupCountText = findViewById(R.id.tvPushupCount)
        // doneButton = findViewById(R.id.btnFinish)
        // blockedAppInfoText = findViewById(R.id.tvBlockedAppName)

        timeBankManager = TimeBankManager(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

        blockedAppPackageNameExtra = intent.getStringExtra(EXTRA_BLOCKED_APP_NAME)
        Log.d(TAG, "PushupActivity started. Blocked app package: $blockedAppPackageNameExtra")

        if (blockedAppPackageNameExtra != null) {
            val friendlyAppName = getAppNameFromPackage(blockedAppPackageNameExtra!!)
            binding.tvBlockedAppName.text = "Time's up for: $friendlyAppName" // Using binding
        } else {
            binding.tvBlockedAppName.text = "Time's up!" // Using binding
        }

        binding.tvPushupCount.text = "$pushupCount" // Using binding
        initializePoseDetector()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE
            )
        }

        binding.btnFinish.visibility = View.VISIBLE // Using binding
        binding.btnFinish.setOnClickListener {
            val secondsPerPushup = 10 // Example: Or load from settings later
            val timeEarned = pushupCount * secondsPerPushup
            timeBankManager.addTimeSeconds(timeEarned) // Use addTimeSeconds

            Toast.makeText(
                this,
                "$pushupCount push-ups added! $timeEarned seconds earned. Total: ${timeBankManager.getTimeSeconds() / 60} mins",
                Toast.LENGTH_LONG
            ).show()

            // If an app was specifically blocked, finishing this activity should allow the user
            // to return to it (or the system to bring it to foreground if it was the last task).
            // AppBlockerService will re-evaluate on next foreground event.
            finish()
        }
    }

    // Helper function to get user-friendly application name from package name
    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val packageManager = applicationContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Failed to get app name for $packageName", e)
            packageName // Fallback to package name if not found
        }
    }

    private fun initializePoseDetector() {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        poseDetector = PoseDetection.getClient(options)
        Log.d(TAG, "Pose Detector Initialized")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider) // Using binding
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isProcessingFrame) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                isProcessingFrame = true
                processImageProxy(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
                Log.d(TAG, "Camera bound to lifecycle")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    if (pose != null && pose.allPoseLandmarks.isNotEmpty()) {
                        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
                        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

                        if (nose != null && leftShoulder != null && rightShoulder != null) {
                            val shoulderY = (leftShoulder.position.y + rightShoulder.position.y) / 2f
                            // Using shoulderY as the primary vertical reference for push-up state
                            val currentY = shoulderY

                            // Ensure previewView has been laid out and has a height
                            if (binding.previewView.height == 0) {
                                imageProxy.close()
                                isProcessingFrame = false
                                return@addOnSuccessListener
                            }

                            // Dynamic Up Reference Initialization/Adjustment
                            // Initialize upReferenceY or adjust if a higher "up" position is detected.
                            // This helps if the initial pose is already somewhat down.
                            if (upReferenceY == -1f || currentY < upReferenceY) {
                                // Only adjust upReferenceY if not in DOWN state or significantly higher than current up ref
                                // This prevents minor fluctuations while in UP state from wrongly setting a lower upReference.
                                if (currentPushupState != PushupState.DOWN || currentY < upReferenceY * (1 - upThresholdFactor * 0.5f) ) {
                                    upReferenceY = currentY
                                    // Log.d(TAG, "New UP reference Y: $upReferenceY")
                                }
                            }

                            // Define thresholds based on a percentage of the previewView height as movement range
                            // This makes it somewhat adaptive to how close the person is.
                            // Consider a fixed pixel range if percentage is too variable.
                            val movementRange = binding.previewView.height * 0.3f // Assume push-up movement is roughly 30% of view height
                            val downThreshold = upReferenceY + (movementRange * downThresholdFactor)
                            val upThreshold = upReferenceY + (movementRange * (downThresholdFactor - upThresholdFactor))

                            // Log.d(TAG, "currentY: $currentY, upRef: $upReferenceY, downThresh: $downThreshold, upThresh: $upThreshold, State: $currentPushupState")


                            when (currentPushupState) {
                                PushupState.UNKNOWN, PushupState.UP -> {
                                    if (currentY > downThreshold) {
                                        currentPushupState = PushupState.DOWN
                                        Log.i(TAG, "STATE CHANGE: -> DOWN (currentY: $currentY > downThreshold: $downThreshold)")
                                    }
                                }
                                PushupState.DOWN -> {
                                    if (currentY < upThreshold) {
                                        currentPushupState = PushupState.UP
                                        pushupCount++
                                        runOnUiThread {
                                            binding.tvPushupCount.text = "$pushupCount"
                                        }
                                        Log.i(TAG, "STATE CHANGE: -> UP (PUSH-UP COUNTED: $pushupCount) (currentY: $currentY < upThreshold: $upThreshold)")
                                        // Update upReferenceY to the new "up" position after completing a rep
                                        // This helps recalibrate if the user shifts slightly.
                                        upReferenceY = currentY
                                    }
                                }
                            }
                        } else {
                            // Landmarks missing, reset state
                            // Log.d(TAG, "Landmarks missing (nose or shoulders)")
                            currentPushupState = PushupState.UNKNOWN
                            upReferenceY = -1f // Reset reference if landmarks are lost
                        }
                    } else {
                        // No pose detected or no landmarks
                        // Log.d(TAG, "No pose or landmarks detected.")
                        currentPushupState = PushupState.UNKNOWN
                        upReferenceY = -1f // Reset reference
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Pose detection failed", e)
                    currentPushupState = PushupState.UNKNOWN // Reset on failure
                    upReferenceY = -1f
                }
                .addOnCompleteListener {
                    imageProxy.close()
                    isProcessingFrame = false
                }
        } else {
            imageProxy.close() // Close if mediaImage is null
            isProcessingFrame = false
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                finish() // Or handle differently
            }
        }
    }

    override fun onBackPressed() {
        // Consider if user should be able to easily back out if they were forced here.
        // For now, allow back press.
        super.onBackPressed()
        // Or show a toast:
        // Toast.makeText(this, "Complete push-ups or use the done button.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::poseDetector.isInitialized) {
            poseDetector.close()
        }
        Log.d(TAG, "PushupActivity destroyed.")
    }
}