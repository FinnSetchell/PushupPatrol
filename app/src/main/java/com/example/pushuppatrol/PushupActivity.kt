package com.example.pushuppatrol // Replace with your actual package name

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
// If you chose the base model instead of accurate:
// import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.collections.List
import kotlin.collections.isNotEmpty
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PushupActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var pushupCountText: TextView
    private lateinit var doneButton: Button

    private lateinit var cameraExecutor: ExecutorService
    // private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider> // Already declared

    private var pushupCount = 0

    // For ML Kit Pose Detection
    private lateinit var poseDetector: PoseDetector
    private var isProcessingFrame = false // To prevent processing multiple frames simultaneously

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

        cameraExecutor = Executors.newSingleThreadExecutor()
        // cameraProviderFuture = ProcessCameraProvider.getInstance(this) // Already initialized

        // Initialize ML Kit Pose Detector
        initializePoseDetector()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST_CODE
            )
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
                        // For now, just log that a pose was detected.
                        // We'll use the pose data in the next micro-goal.
                        Log.d(TAG, "Pose detected! Number of landmarks: ${pose.allPoseLandmarks.size}")
                        // You can also log specific landmark positions if you want to explore
                        // val nose = poses[0].getPoseLandmark(PoseLandmark.NOSE)
                        // if (nose != null) {
                        //     Log.d(TAG, "Nose position: ${nose.position.x}, ${nose.position.y}")
                        // }
                    } else {
                        Log.d(TAG, "No pose detected in this frame (or pose has no landmarks).")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Pose detection failed", e)
                }
                .addOnCompleteListener {
                    // Crucial: close the imageProxy when processing is done
                    // to allow the next frame to be processed.
                    imageProxy.close()
                    isProcessingFrame = false // Allow next frame to be processed
                }
        } else {
            Log.e(TAG, "MediaImage is null, skipping processing.")
            imageProxy.close() // Still need to close it
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