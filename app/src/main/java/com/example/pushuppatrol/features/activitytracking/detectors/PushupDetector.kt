package com.example.pushuppatrol.features.activitytracking.detectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.pushuppatrol.R
import com.example.pushuppatrol.features.activitytracking.ActivityConfiguration
import com.example.pushuppatrol.features.activitytracking.ActivityProgressListener
import com.example.pushuppatrol.features.activitytracking.PoseUpdateListener
import com.example.pushuppatrol.features.activitytracking.TrackableActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs // For potential future use, not directly in current logic

// Interface to send detailed debug data back to the Activity
interface PoseProcessDebugListener {
    fun onPoseDebugInfo(debugText: String)
}

class PushupDetector : TrackableActivity {

    private lateinit var cameraExecutor: ExecutorService
    private var pushupCount = 0

    private enum class PushupState { UP, DOWN, UNKNOWN }
    private var currentPushupState: PushupState = PushupState.UNKNOWN
    private var poseUpdateListener: PoseUpdateListener? = null

    // --- Detection Parameters (CRITICAL: THESE NEED EXTENSIVE TUNING) ---
    // Assumes Y-coordinate INCREASES as the landmark goes DOWN (closer to floor)
    // If Y DECREASES downwards, invert comparisons and potentially min/max logic.

    // Factor of the total observed movement range to define "down" and "up" thresholds.
    private val movementRangeFactorDown = 0.70f // Must go at least 70% of the way down from initial UP
    private val movementRangeFactorUp = 0.60f   // Must come at least 35% of the way up from the lowest DOWN

    // Stores the highest (numerically smallest Y if origin is top-left & Y increases downwards)
    // and lowest (numerically largest Y) points observed for the relevant landmark (e.g., nose).
    private var minObservedY: Float? = null // Represents the "highest" point in the UP position
    private var maxObservedY: Float? = null // Represents the "lowest" point in the DOWN position

    // Minimum vertical displacement (in landmark coordinate units) to be considered a valid push-up movement.
    // This helps filter out minor jitters. This value is highly dependent on image resolution and distance.
    // Start with a value like 20-50 and adjust based on logged Y values from actual movement.
    private val minimumVerticalDisplacement = 30f // Placeholder: ADJUST BASED ON TESTING!

    // Confidence threshold for landmarks
    private val landmarkConfidenceThreshold = 0.5f // Can be slightly lower if nose is the primary
    // --- End of Detection Parameters ---

    private lateinit var poseDetector: PoseDetector
    private var isProcessingFrame = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null

    private var activityListener: ActivityProgressListener? = null
    private var lifecycleOwner: LifecycleOwner? = null
    var previewSurfaceProvider: Preview.SurfaceProvider? = null

    // Listener for sending debug data back to the Activity
    var debugListener: PoseProcessDebugListener? = null


    companion object {
        private const val TAG = "PushupDetector"
    }

    override fun startTracking(
        context: Context,
        listener: ActivityProgressListener,
        config: ActivityConfiguration?
    ) {
        if (context !is LifecycleOwner) {
            Log.e(TAG, "Context provided to startTracking is not a LifecycleOwner.")
            listener.onError("Initialization Error", "Context must be a LifecycleOwner.")
            return
        }
        this.lifecycleOwner = context
        this.activityListener = listener
        // If the listener also implements PoseProcessDebugListener, assign it
        if (listener is PoseProcessDebugListener) {
            this.debugListener = listener
        }
        if (listener is PoseUpdateListener) {
            this.poseUpdateListener = listener
        }


        cameraExecutor = Executors.newSingleThreadExecutor()
        initializePoseDetector()

        if (previewSurfaceProvider == null) {
            Log.e(TAG, "PreviewSurfaceProvider is not set.")
            listener.onError("Preview setup error", "PreviewSurfaceProvider missing.")
            return
        }
        resetDetectionState() // Reset state variables when starting
        startCamera(context)
    }

    private fun resetDetectionState() {
        pushupCount = 0
        currentPushupState = PushupState.UNKNOWN
        minObservedY = null
        maxObservedY = null
        activityListener?.onProgressUpdate(pushupCount, getUnitName(lifecycleOwner as Context))
        Log.d(TAG, "Push-up detection state reset.")
    }


    override fun stopTracking() {
        Log.d(TAG, "stopTracking called")
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error during camera unbinding", e)
        } finally {
            cameraProvider = null
            imageAnalysis = null
            preview = null
        }

        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        if (::poseDetector.isInitialized) {
            try {
                poseDetector.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing pose detector", e)
            }
        }
        activityListener = null
        lifecycleOwner = null
        previewSurfaceProvider = null
        debugListener = null
        poseUpdateListener?.onClearPose()
        poseUpdateListener = null
        Log.d(TAG, "Resources released.")
    }

    override fun getRequiredPermissions(): Array<String> = arrayOf(Manifest.permission.CAMERA)
    override fun getDisplayName(context: Context): String = context.getString(R.string.activity_type_pushups)
    override fun getUnitName(context: Context): String = context.getString(R.string.unit_name_reps)

    private fun initializePoseDetector() {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        poseDetector = PoseDetection.getClient(options)
        Log.d(TAG, "Pose Detector Initialized")
    }

    private fun startCamera(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
                activityListener?.onError("Camera Error", "Could not initialize camera provider.")
                activityListener?.onSetupStateChanged("Camera setup failed", false)
                return@addListener
            }

            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewSurfaceProvider)
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis!!.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isProcessingFrame) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                isProcessingFrame = true
                processImageProxy(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider!!.unbindAll()
                cameraProvider!!.bindToLifecycle(
                    lifecycleOwner!!,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d(TAG, "Camera bound to lifecycle.")
                activityListener?.onSetupStateChanged("Camera ready", true)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                activityListener?.onError("Camera Error", exc.message ?: "Failed to bind camera.")
                activityListener?.onSetupStateChanged("Camera setup failed", false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isProcessingFrame = false
            poseUpdateListener?.onClearPose()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val imageWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.width else imageProxy.height
        val imageHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) imageProxy.height else imageProxy.width
        val isFrontCamera = cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true

        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                // Call detectPushup first, as it calculates/updates these values
                detectPushup(pose)

                // Now, retrieve the values from the detector's state to pass to the listener
                // (Assuming these are properties of PushupDetector: minObservedY, maxObservedY)
                // And also the calculated thresholds and current Y being used.

                // Need to ensure the values passed are the ones from the *current frame's analysis*
                // This requires detectPushup to make them available or return them.
                // For now, let's assume detectPushup has updated the class members:
                // this.minObservedY, this.maxObservedY, and can provide currentY and thresholds

                var frameMinY: Float? = null
                var frameMaxY: Float? = null
                var frameDownThr: Float? = null
                var frameUpThr: Float? = null
                var frameCurrentY: Float? = null

                val noseLandmark = pose.getPoseLandmark(PoseLandmark.NOSE)
                if (noseLandmark != null && noseLandmark.inFrameLikelihood >= landmarkConfidenceThreshold) {
                    frameCurrentY = noseLandmark.position.y // The 'currentY' used in detectPushup
                }

                // Get the current values of these from the detector's state
                frameMinY = this.minObservedY
                frameMaxY = this.maxObservedY

                if (this.minObservedY != null && this.maxObservedY != null) {
                    val movementRange = this.maxObservedY!! - this.minObservedY!!
                    if (movementRange >= this.minimumVerticalDisplacement) { // Check if range is valid
                        frameDownThr = this.minObservedY!! + (movementRange * this.movementRangeFactorDown)
                        frameUpThr = this.minObservedY!! + (movementRange * this.movementRangeFactorUp)
                    }
                }

                poseUpdateListener?.onPoseDetected(
                    pose,
                    imageWidth,
                    imageHeight,
                    isFrontCamera,
                    frameMinY,
                    frameMaxY,
                    frameDownThr,
                    frameUpThr,
                    frameCurrentY // The 'currentY' used in detectPushup for this frame
                )
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Pose detection failed", e)
                activityListener?.onError("Pose Detection Error", e.message)
                currentPushupState = PushupState.UNKNOWN
                poseUpdateListener?.onClearPose()
                debugListener?.onPoseDebugInfo("Error: Pose detection failed. ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
                isProcessingFrame = false
                isProcessingFrame = false
            }
    }

    private fun detectPushup(pose: Pose) {
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        // val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER) // Keep for potential future use or if nose is unreliable
        // val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

        val debugStringBuilder = StringBuilder()

        // Using NOSE as the primary landmark for vertical movement detection.
        // This is often more stable and visible than shoulders in the described camera setup.
        if (nose != null && nose.inFrameLikelihood >= landmarkConfidenceThreshold) {
            val currentY = nose.position.y // Y_INCREASES_DOWNWARDS is assumed. If not, logic needs inversion.

            debugStringBuilder.appendLine("NoseY: ${"%.2f".format(currentY)}")
            debugStringBuilder.appendLine("NoseConf: ${"%.2f".format(nose.inFrameLikelihood)}")


            // Initialize or update observed Y range
            if (minObservedY == null || currentY < minObservedY!!) {
                minObservedY = currentY
            }
            if (maxObservedY == null || currentY > maxObservedY!!) {
                maxObservedY = currentY
            }

            debugStringBuilder.appendLine("MinY: ${minObservedY?.let { "%.2f".format(it) } ?: "N/A"}")
            debugStringBuilder.appendLine("MaxY: ${maxObservedY?.let { "%.2f".format(it) } ?: "N/A"}")


            if (minObservedY != null && maxObservedY != null) {
                val movementRange = maxObservedY!! - minObservedY!!
                debugStringBuilder.appendLine("Range: ${"%.2f".format(movementRange)}")

                if (movementRange < minimumVerticalDisplacement) {
                    debugStringBuilder.appendLine("Status: Range < MinDisplacement. Waiting for movement.")
                    // Log.d(TAG, "Movement range ($movementRange) too small, waiting for clearer movement.")
                    // No state change yet. State remains as is or UNKNOWN.
                    debugListener?.onPoseDebugInfo(debugStringBuilder.toString())
                    return
                }

                val downThreshold = minObservedY!! + (movementRange * movementRangeFactorDown)
                val upThreshold = minObservedY!! + (movementRange * movementRangeFactorUp)
                // Ensure upThreshold is not above downThreshold if factors are misconfigured or range is tiny
                // val effectiveUpThreshold = if (upThreshold > downThreshold) downThreshold - 1f else upThreshold


                debugStringBuilder.appendLine("DownThr: ${"%.2f".format(downThreshold)}")
                debugStringBuilder.appendLine("UpThr: ${"%.2f".format(upThreshold)}")
                debugStringBuilder.appendLine("State: $currentPushupState")

                when (currentPushupState) {
                    PushupState.UNKNOWN -> {
                        // Initial state: if currentY is closer to minObservedY, assume UP, else DOWN.
                        // This helps initialize the state once a minimal range is established.
                        if (currentY < (minObservedY!! + movementRange / 2)) {
                            currentPushupState = PushupState.UP
                            Log.i(TAG, "STATE INIT: -> UP (NoseY: $currentY)")
                            debugStringBuilder.appendLine("NewState: UP (Init)")
                        } else {
                            currentPushupState = PushupState.DOWN
                            Log.i(TAG, "STATE INIT: -> DOWN (NoseY: $currentY)")
                            debugStringBuilder.appendLine("NewState: DOWN (Init)")
                        }
                    }
                    PushupState.UP -> {
                        if (currentY >= downThreshold) {
                            currentPushupState = PushupState.DOWN
                            Log.i(TAG, "STATE CHANGE: UP -> DOWN (NoseY: $currentY >= $downThreshold)")
                            // When transitioning to DOWN, we can choose to reset maxObservedY
                            // to more accurately capture the bottom of *this specific* rep.
                            // Or, keep it to maintain the overall max range seen so far.
                            // For now, let's allow maxObservedY to keep growing if they go lower.
                            // maxObservedY = currentY // Option: reset to current if you want each rep's low point
                            debugStringBuilder.appendLine("NewState: DOWN")
                        } else {
                            // If user goes even higher while in UP state, update minObservedY
                            if (currentY < minObservedY!!) minObservedY = currentY // Keep calibrating highest point
                        }
                    }
                    PushupState.DOWN -> {
                        if (currentY <= upThreshold) {
                            currentPushupState = PushupState.UP
                            pushupCount++
                            activityListener?.onProgressUpdate(
                                pushupCount,
                                getUnitName(lifecycleOwner as Context)
                            )
                            Log.i(TAG, "STATE CHANGE: DOWN -> UP (REP: $pushupCount) (NoseY: $currentY <= $upThreshold)")
                            // When transitioning to UP, we can choose to reset minObservedY
                            // minObservedY = currentY // Option: reset to current if you want each rep's high point
                            debugStringBuilder.appendLine("NewState: UP (REP!)")
                        } else {
                            // If user goes even lower while in DOWN state, update maxObservedY
                            if (currentY > maxObservedY!!) maxObservedY = currentY // Keep calibrating lowest point
                        }
                    }
                }
            } else {
                debugStringBuilder.appendLine("Status: Waiting for min/max Y.")
            }
        } else {
            // Nose landmark not reliably detected
            debugStringBuilder.appendLine("Status: Nose landmark not detected or low confidence.")
            poseUpdateListener?.onClearPose()
            if (currentPushupState != PushupState.UNKNOWN) {
                Log.d(TAG, "Nose landmark lost or low confidence.")
                // Consider how to handle this. Resetting state immediately might be too aggressive.
                // currentPushupState = PushupState.UNKNOWN;
                // minObservedY = null; maxObservedY = null; // Option: reset calibration if lost for too long
            }
        }
        debugListener?.onPoseDebugInfo(debugStringBuilder.toString())
    }
}
