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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PushupActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var pushupCountText: TextView
    private lateinit var doneButton: Button
    private lateinit var blockedAppInfoText: TextView // To display which app was blocked

    private lateinit var cameraExecutor: ExecutorService
    private var pushupCount = 0
    private enum class PushupState {
        UP, DOWN, UNKNOWN
    }
    private var currentPushupState: PushupState = PushupState.UNKNOWN
    private var upReferenceY: Float = -1f
    private val downThresholdFactor = 0.25f
    private val upThresholdFactor = 0.15f

    private lateinit var poseDetector: PoseDetector
    private var isProcessingFrame = false

    private lateinit var timeBankManager: TimeBankManager
    private var blockedAppNameExtra: String? = null // To store the package name passed via intent

    companion object {
        private const val TAG = "PushupActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        // Ensure this matches what AppBlockerService uses to send the extra
        const val EXTRA_BLOCKED_APP_NAME = "com.example.pushuppatrol.EXTRA_BLOCKED_APP_NAME"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pushup) // Ensure this layout exists and has the views

        previewView = findViewById(R.id.previewView)
        pushupCountText = findViewById(R.id.tvPushupCount)
        doneButton = findViewById(R.id.btnFinish)
        blockedAppInfoText = findViewById(R.id.tvBlockedAppName) // Make sure you add this TextView to your activity_pushup.xml

        timeBankManager = TimeBankManager(applicationContext)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Get the blocked app package name from the intent
        blockedAppNameExtra = intent.getStringExtra(EXTRA_BLOCKED_APP_NAME)
        Log.d(TAG, "PushupActivity started. App that ran out of time: $blockedAppNameExtra")

        if (blockedAppNameExtra != null) {
            // Try to get the user-friendly app name
            val friendlyAppName = getAppNameFromPackage(blockedAppNameExtra!!)
            blockedAppInfoText.text = "Time's up for: $friendlyAppName"
        } else {
            blockedAppInfoText.text = "Time's up!" // Default message
        }

        pushupCountText.text = "$pushupCount"
        initializePoseDetector()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE
            )
        }

        doneButton.visibility = View.VISIBLE
        doneButton.setOnClickListener {
            // Original logic: add pushups to time bank and finish
            // You might want to adjust how much time is added per session or per pushup later
            timeBankManager.addPushups(pushupCount) // Assumes TimeBankManager.addPushups() exists
            Toast.makeText(this, "$pushupCount push-ups added! Total time: ${timeBankManager.getTimeSeconds() / 60} mins", Toast.LENGTH_LONG).show()

            // If a specific app was blocked, you might want to send a broadcast or signal
            // that time has been earned for it, so AppBlockerService can allow access again.
            // For now, just finishing and the user can try reopening.
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
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                if (isProcessingFrame) {
                    imageProxy.close()
                    return@Analyzer
                }
                isProcessingFrame = true
                processImageProxy(imageProxy)
            })

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
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
                            val currentY = shoulderY

                            if (previewView.height == 0) {
                                imageProxy.close()
                                isProcessingFrame = false
                                return@addOnSuccessListener
                            }

                            if (upReferenceY == -1f || currentY < upReferenceY) {
                                if (currentPushupState != PushupState.DOWN || currentY < upReferenceY * (1 - upThresholdFactor * 0.5f) ) {
                                    upReferenceY = currentY
                                }
                            }

                            val movementRange = previewView.height * 0.3
                            val downThreshold = upReferenceY + (movementRange * downThresholdFactor)
                            val upThreshold = upReferenceY + (movementRange * (downThresholdFactor - upThresholdFactor))

                            when (currentPushupState) {
                                PushupState.UNKNOWN, PushupState.UP -> {
                                    if (currentY > downThreshold) {
                                        currentPushupState = PushupState.DOWN
                                    }
                                }
                                PushupState.DOWN -> {
                                    if (currentY < upThreshold) {
                                        currentPushupState = PushupState.UP
                                        pushupCount++
                                        runOnUiThread {
                                            pushupCountText.text = "$pushupCount"
                                        }
                                        upReferenceY = currentY
                                    }
                                }
                            }
                        } else {
                            currentPushupState = PushupState.UNKNOWN
                            upReferenceY = -1f
                        }
                    } else {
                        currentPushupState = PushupState.UNKNOWN
                        upReferenceY = -1f
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Pose detection failed", e)
                    currentPushupState = PushupState.UNKNOWN
                    upReferenceY = -1f
                }
                .addOnCompleteListener {
                    imageProxy.close()
                    isProcessingFrame = false
                }
        } else {
            imageProxy.close()
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

    // Optional: Prevent back press from easily dismissing the activity if time is up.
    override fun onBackPressed() {
        // super.onBackPressed() // Comment out to disable back button
        Toast.makeText(this, "Please complete your push-ups to earn more time.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::poseDetector.isInitialized) {
            poseDetector.close()
        }
    }
}