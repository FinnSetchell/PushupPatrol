package com.example.pushuppatrol.activitytracking.detectors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.pushuppatrol.R
import com.example.pushuppatrol.activitytracking.ActivityConfiguration
import com.example.pushuppatrol.activitytracking.ActivityProgressListener
import com.example.pushuppatrol.activitytracking.TrackableActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PushupDetector : TrackableActivity {

    private lateinit var cameraExecutor: ExecutorService
    private var pushupCount = 0

    private enum class PushupState { UP, DOWN, UNKNOWN }
    private var currentPushupState: PushupState = PushupState.UNKNOWN
    private var upReferenceY: Float = -1f
    private val downThresholdFactor = 0.25f // These could become configurable via ActivityConfiguration
    private val upThresholdFactor = 0.15f

    private lateinit var poseDetector: PoseDetector
    private var isProcessingFrame = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null // For the detector to manage its Preview

    private var activityListener: ActivityProgressListener? = null
    private var applicationContext: Context? = null // To hold context for longer operations if needed

    // For accessing PreviewView from the Activity - this is one way to link them.
    // The Activity will need to provide this.
    // Consider if this should be part of ActivityConfiguration for pushups.
    var previewViewProvider: (() -> Preview.SurfaceProvider)? = null


    companion object {
        private const val TAG = "PushupDetector"
    }

    // --- TrackableActivity Implementation ---

    override fun startTracking(
        context: Context,
        listener: ActivityProgressListener,
        config: ActivityConfiguration?
    ) {
        this.applicationContext = context.applicationContext // Use application context to avoid leaks
        this.activityListener = listener

        // Initialize things that require context or listener
        cameraExecutor = Executors.newSingleThreadExecutor()
        initializePoseDetector(this.applicationContext!!) // Ensure context is not null

        // Permission Check: The calling Activity should do this BEFORE calling startTracking.
        // If startTracking is called, we assume permissions are granted.
        // Alternatively, the detector could re-check and call listener.onPermissionMissing().
        // For now, we assume PushupActivity handles it.

        if (previewViewProvider == null) {
            Log.e(TAG, "PreviewView provider is not set before calling startTracking.")
            listener.onError("Preview setup error", "PreviewView provider missing.")
            return
        }

        startCamera(context) // Pass context
    }

    override fun stopTracking() {
        Log.d(TAG, "stopTracking called")
        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
            imageAnalysis?.clearAnalyzer() // Important to release the analyzer
            imageAnalysis = null
            preview = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during camera unbinding or analyzer clearing", e)
        }

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        if (::poseDetector.isInitialized) {
            poseDetector.close()
        }
        // Release listener and context to prevent leaks
        activityListener = null
        applicationContext = null
        previewViewProvider = null // Release the provider
        Log.d(TAG, "Resources released.")
    }

    override fun getRequiredPermissions(): Array<String> {
        return arrayOf(Manifest.permission.CAMERA)
    }

    override fun getDisplayName(context: Context): String {
        // Placeholder. Later, this should come from strings.xml
        // return context.getString(R.string.activity_pushups_name)
        return "Push-ups"
    }

    override fun getUnitName(context: Context): String {
        // Placeholder. Later, this should come from strings.xml
        // return context.getString(R.string.unit_reps)
        return "reps"
    }

    // --- Push-up Specific Logic (Moved and Adapted from PushupActivity) ---

    private fun initializePoseDetector(context: Context) { // Added context param
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        poseDetector = PoseDetection.getClient(options) // ML Kit doesn't always need context for this client
        Log.d(TAG, "Pose Detector Initialized")
    }

    private fun startCamera(context: Context) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            var isPreviewActuallySetup = false // Flag to track if preview was successfully set up

            preview = Preview.Builder().build().also {
                val surfaceProviderFromActivity = previewViewProvider?.invoke() // Call on main thread
                if (surfaceProviderFromActivity == null) {
                    Log.e(TAG, "SurfaceProvider from Activity is null, camera preview cannot start.")
                    activityListener?.onError("Camera Error", "Preview surface not available.")
                    // Do not return@addListener immediately if imageAnalysis is still desired
                    // but mark that preview isn't setup.
                    // Or, decide if this is a fatal error for the whole camera setup.
                    // For now, let's assume if preview fails, we might still want analysis,
                    // but flag it. A stricter approach could be to return.
                } else {
                    it.setSurfaceProvider(surfaceProviderFromActivity)
                    isPreviewActuallySetup = true // Mark as setup
                }
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            // Pass the determined 'isPreviewActuallySetup' state to the analyzer's lambda
            imageAnalysis!!.setAnalyzer(cameraExecutor) { imageProxy ->
                if (isProcessingFrame) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                isProcessingFrame = true
                // Use the flag captured on the main thread
                processImageProxy(imageProxy, isPreviewActuallySetup)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider!!.unbindAll()
                // Bind preview only if it was successfully set up.
                // If isPreviewActuallySetup is false, preview might be null or its surfaceProvider not set.
                // CameraX bindToLifecycle can handle a null preview use case gracefully,
                // it simply won't bind it.
                cameraProvider!!.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    cameraSelector,
                    if (isPreviewActuallySetup) preview else null, // Conditionally bind preview
                    imageAnalysis
                )
                Log.d(TAG, "Camera bound to lifecycle. Preview setup: $isPreviewActuallySetup")
                if (isPreviewActuallySetup) {
                    activityListener?.onSetupStateChanged("Camera ready", true)
                } else {
                    // If preview wasn't setup but analysis might be, this message might need adjustment.
                    // Or, if preview is essential, this path shouldn't be reached if surfaceProvider was null.
                    // For now, let's assume "Camera ready" implies at least analysis is attempted.
                    // A more nuanced state update might be needed.
                    activityListener?.onSetupStateChanged("Camera ready (analysis only, preview issue)", true)
                    Log.w(TAG, "Camera bound for analysis, but preview had an issue.")
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                activityListener?.onError("Camera Error", exc.message ?: "Failed to bind camera.")
                activityListener?.onSetupStateChanged("Camera setup failed", false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy, isPreviewSetup: Boolean) { // Added isPreviewSetup
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            isProcessingFrame = false
            return
        }

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

                        // If preview is not set up, we can't get previewView.height
                        // This part needs careful handling. For now, we proceed assuming it might be available
                        // or this logic needs to adapt (e.g. use fixed values or config if preview not visible)
                        // This highlights complexity if the detector doesn't "own" a view.
                        // For now, let's assume it's okay, but it's a weak point.
                        // A better approach might be to not depend on a view's height directly here.
                        // Or, pass screen/preview dimensions via ActivityConfiguration.
                        //
                        // Let's defer improving the `movementRange` calculation for now,
                        // and assume it's not directly tied to a visible previewView's height.
                        // We can make `movementRange` a fixed value or configurable.
                        // For this refactor, let's try a fixed estimated range.
                        // A more robust solution involves passing viewport dimensions.

                        val estimatedMovementRangeFactor = 0.3f // Arbitrary factor, assuming normalized coordinates or relative movement.
                        // This needs to be calibrated based on how ML Kit pose landmarks are scaled.
                        // ML Kit landmarks are usually in the input image's coordinate space.

                        if (upReferenceY == -1f || currentY < upReferenceY) {
                            // Keep the original logic for upReferenceY update
                            if (currentPushupState != PushupState.DOWN || currentY < upReferenceY * (1 - upThresholdFactor * 0.5f) ) {
                                upReferenceY = currentY
                            }
                        }

                        // This is a placeholder. The original logic used binding.previewView.height.
                        // That direct view access is what we want to remove from the detector.
                        // For a first pass, let's use a less view-dependent placeholder logic.
                        // Ideally, this range would be calibrated or configured.
                        // Let's assume `upReferenceY` gives a baseline for the "up" state.
                        // And the "down" state is a certain displacement from that.
                        // This calculation needs to be relative to landmark positions, not view pixels.
                        val movementDisplacementThreshold = 70 // Placeholder: Needs to be in landmark coordinate units
                        // This value is a guess and will need tuning.
                        // It represents the expected vertical displacement for a push-up.


                        val downThreshold = upReferenceY + movementDisplacementThreshold * downThresholdFactor
                        val upThreshold = upReferenceY + movementDisplacementThreshold * (1 - upThresholdFactor) // Adjusted logic

                        when (currentPushupState) {
                            PushupState.UNKNOWN, PushupState.UP -> {
                                if (currentY > downThreshold) {
                                    currentPushupState = PushupState.DOWN
                                    Log.i(TAG, "STATE CHANGE: -> DOWN (NoseY: ${nose.position.y}, ShoulderY: $currentY > downThreshold: $downThreshold, upRef: $upReferenceY)")
                                }
                            }
                            PushupState.DOWN -> {
                                if (currentY < upThreshold) {
                                    currentPushupState = PushupState.UP
                                    pushupCount++
                                    activityListener?.onProgressUpdate(pushupCount, getUnitName(applicationContext!!))
                                    Log.i(TAG, "STATE CHANGE: -> UP (PUSH-UP COUNTED: $pushupCount) (NoseY: ${nose.position.y}, ShoulderY: $currentY < upThreshold: $upThreshold, upRef: $upReferenceY)")
                                    upReferenceY = currentY // Update reference when back in UP state
                                }
                            }
                        }
                    } else {
                        // Not all required landmarks are visible
                        if (currentPushupState != PushupState.UNKNOWN) {
                            Log.d(TAG, "Landmarks lost, state set to UNKNOWN")
                            currentPushupState = PushupState.UNKNOWN
                            // Do not reset upReferenceY here, as user might just be temporarily out of view
                        }
                    }
                } else {
                    // No pose detected or no landmarks
                    if (currentPushupState != PushupState.UNKNOWN) {
                        Log.d(TAG, "No pose detected, state set to UNKNOWN")
                        currentPushupState = PushupState.UNKNOWN
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Pose detection failed", e)
                activityListener?.onError("Pose Detection Error", e.message)
                currentPushupState = PushupState.UNKNOWN
            }
            .addOnCompleteListener {
                imageProxy.close()
                isProcessingFrame = false
            }
    }
}