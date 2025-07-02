package com.example.pushuppatrol // Replace with your actual package name

// If you chose the base model instead of accurate:
// import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
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

    private lateinit var cameraExecutor: ExecutorService
    // private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider> // Already declared

    // For push-up counting logic
    private var pushupCount = 0
    private enum class PushupState {
        UP, DOWN, UNKNOWN // UNKNOWN or NEUTRAL state initially
    }
    private var currentPushupState: PushupState = PushupState.UNKNOWN
    private var lastNoseY: Float = 0.0f

    // Thresholds - these might need tuning based on testing
    // Represents how much the nose has to move down from its highest point to be considered 'DOWN'
    private val downThresholdFactor = 0.25f // e.g., 25% of preview height from initial 'UP'
    // Represents how much the nose has to move up from a 'DOWN' state to be considered 'UP'
    private val upThresholdFactor = 0.15f // e.g., 15% of preview height from 'DOWN'

    private var upReferenceY: Float = -1f // Stores the Y of the nose when in a clear UP state

    // For ML Kit Pose Detection
    private lateinit var poseDetector: PoseDetector
    private var isProcessingFrame = false // To prevent processing multiple frames simultaneously

    // For Time Bank management
    private lateinit var timeBankManager: TimeBankManager // New

    companion object {
        private const val TAG = "PushupActivity"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pushup)

        previewView = findViewById(R.id.previewView)
        pushupCountText = findViewById(R.id.pushupCountText)
        doneButton = findViewById(R.id.doneButton)
        timeBankManager = TimeBankManager(applicationContext)

        pushupCountText.text = "Push-ups: $pushupCount"

        cameraExecutor = Executors.newSingleThreadExecutor()
        // cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // Initialize ML Kit Pose Detector
        initializePoseDetector()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE
            )
        }

        // Make the Done button visible (it was GONE in XML)
        // and set its OnClickListener
        doneButton.visibility = View.VISIBLE // Make it visible
        doneButton.setOnClickListener {
            timeBankManager.addPushups(pushupCount)
            Toast.makeText(this, "$pushupCount push-ups added! Total time: ${timeBankManager.getTimeSeconds() / 60} mins", Toast.LENGTH_LONG).show()
            finish() // Close PushupActivity
        }
    }

    private fun initializePoseDetector() {
        // Using the accurate model
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE) // For live video
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

            // Setup ImageAnalysis use case
            val imageAnalysis = ImageAnalysis.Builder()
                // Set target resolution if needed, otherwise CameraX will determine optimal
                // .setTargetResolution(Size(640, 480)) // Example
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Process only the latest frame
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                if (isProcessingFrame) {
                    imageProxy.close() // Close the image if another is being processed
                    return@Analyzer
                }
                isProcessingFrame = true
                processImageProxy(imageProxy)
            })

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis // Add imageAnalysis here
                )
                Log.d(TAG, "Camera started successfully with Preview and ImageAnalysis")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError") // Needed for imageProxy.image
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    if (pose != null && pose.allPoseLandmarks.isNotEmpty()) {
                        // Log.d(TAG, "Pose detected! Number of landmarks: ${pose.allPoseLandmarks.size}") // Keep for debugging if needed

                        // --- START PUSH-UP LOGIC (Micro-Goal 1.3) ---
                        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
                        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

                        if (nose != null && leftShoulder != null && rightShoulder != null) {
                            val noseY = nose.position.y
                            // Use average shoulder Y as a more stable reference than just nose
                            val shoulderY = (leftShoulder.position.y + rightShoulder.position.y) / 2f

                            // Use shoulderY for state determination for more robustness against head tilting
                            val currentY = shoulderY

                            if (previewView.height == 0) { // Ensure previewView is laid out
                                imageProxy.close()
                                isProcessingFrame = false
                                return@addOnSuccessListener
                            }

                            // Initialize upReferenceY if not set, or if user is clearly higher
                            if (upReferenceY == -1f || currentY < upReferenceY) {
                                // Only set new upReferenceY if they are significantly higher than a potential 'DOWN' state
                                // or if current state isn't already DOWN (to avoid setting UP when they are actually moving up from DOWN)
                                if (currentPushupState != PushupState.DOWN || currentY < upReferenceY * (1 - upThresholdFactor * 0.5f) ) {
                                    upReferenceY = currentY
                                    Log.d(TAG, "New UP reference Y: $upReferenceY")
                                }
                            }


                            // Define dynamic thresholds based on previewView height
                            // This is a simple approach. More advanced would be normalizing coordinates.
                            val movementRange = previewView.height * 0.3 // Assume push-up movement is roughly 30% of view height
                            val downThreshold = upReferenceY + (movementRange * downThresholdFactor)
                            val upThreshold = upReferenceY + (movementRange * (downThresholdFactor - upThresholdFactor)) // upThreshold is higher than downThreshold

                            // Log Y values and thresholds for tuning:
                            // Log.d(TAG, "Nose Y: $noseY, Shoulder Y: $currentY, UpRef: $upReferenceY, DownThresh: $downThreshold, UpThresh: $upThreshold, State: $currentPushupState")


                            when (currentPushupState) {
                                PushupState.UNKNOWN, PushupState.UP -> {
                                    if (currentY > downThreshold) {
                                        currentPushupState = PushupState.DOWN
                                        Log.d(TAG, "STATE CHANGE: -> DOWN")
                                    }
                                }
                                PushupState.DOWN -> {
                                    if (currentY < upThreshold) {
                                        currentPushupState = PushupState.UP
                                        pushupCount++
                                        Log.d(TAG, "STATE CHANGE: -> UP (COUNT: $pushupCount)")
                                        runOnUiThread { // Update UI on the main thread
                                            pushupCountText.text = "Push-ups: $pushupCount"
                                        }
                                        // Reset upReferenceY to current position after a successful push-up
                                        // to adapt to user possibly shifting position slightly.
                                        upReferenceY = currentY
                                    }
                                }
                            }
                        } else {
                            // Nose or shoulders not detected, reset state or handle as needed
                            currentPushupState = PushupState.UNKNOWN
                            upReferenceY = -1f // Reset reference if key landmarks are lost
                            Log.d(TAG, "Nose or shoulders not visible, state UNKNOWN")
                        }
                        // --- END PUSH-UP LOGIC ---

                    } else {
                        // Log.d(TAG, "No pose detected (or pose has no landmarks). State UNKNOWN.")
                        currentPushupState = PushupState.UNKNOWN
                        upReferenceY = -1f // Reset reference if pose is lost
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Pose detection failed", e)
                    currentPushupState = PushupState.UNKNOWN // Reset state on failure
                    upReferenceY = -1f
                }
                .addOnCompleteListener {
                    imageProxy.close()
                    isProcessingFrame = false
                }
        } else {
            Log.e(TAG, "MediaImage is null, skipping processing.")
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
                Toast.makeText(this, "Camera permission is required to count push-ups.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::poseDetector.isInitialized) { // Check if initialized before closing
            poseDetector.close() // Release ML Kit resources
        }
    }
}