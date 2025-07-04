package com.example.pushuppatrol.activitytracking.detectors

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.pushuppatrol.R // For string resources
import com.example.pushuppatrol.activitytracking.ActivityProgressListener
import com.example.pushuppatrol.activitytracking.TrackableActivity
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SquatDetector : TrackableActivity {

    private var isTrackingActive: Boolean = false
    private lateinit var listener: ActivityProgressListener
    private lateinit var cameraExecutor: ExecutorService // For camera operations
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // For providing the PreviewView's SurfaceProvider to this detector
    var previewViewProvider: (() -> Preview.SurfaceProvider?)? = null

    companion object {
        private const val TAG = "SquatDetector"
    }

    override fun getRequiredPermissions(): Array<String> {
        return arrayOf(Manifest.permission.CAMERA)
    }

    override fun getDisplayName(context: Context): String {
        return context.getString(R.string.activity_name_squats_beta) // Using a string resource
    }

    override fun startTracking(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        listener: ActivityProgressListener,
        previewSurfaceProvider: Preview.SurfaceProvider? // Optional direct surface provider
    ) {
        this.listener = listener
        this.cameraExecutor = Executors.newSingleThreadExecutor() // Create an executor

        // Check for permissions first (though the calling Activity should handle this)
        if (getRequiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            Log.d(TAG, "Starting squat tracking (simulated)...")

            // Use the provided lambda for SurfaceProvider if available, otherwise the direct one
            val surfaceProviderForPreview = previewViewProvider?.invoke() ?: previewSurfaceProvider

            if (surfaceProviderForPreview == null) {
                Log.e(TAG, "PreviewSurfaceProvider is null. Cannot start camera preview for SquatDetector.")
                this.listener.onError("Camera preview setup failed for Squats.", "SurfaceProvider was null.")
                this.listener.onSetupStateChanged("Squat Detector: Preview Error", false)
                return
            }

            cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture?.addListener({
                try {
                    cameraProvider = cameraProviderFuture?.get()
                    if (cameraProvider == null) {
                        Log.e(TAG, "CameraProvider is null, cannot start camera.")
                        this.listener.onError("Failed to get CameraProvider for Squats.", null)
                        this.listener.onSetupStateChanged("Squat Detector: Camera Error", false)
                        return
                    }

                    // Build the Preview use case
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(surfaceProviderForPreview)
                    }

                    // Unbind all previous use cases before rebinding
                    cameraProvider?.unbindAll()

                    // Bind the Preview use case to the camera
                    // For now, we don't add an ImageAnalysis use case as we're not processing frames
                    cameraProvider?.bindToLifecycle(lifecycleOwner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview)

                    isTrackingActive = true
                    Log.i(TAG, "Camera preview started for SquatDetector. Squat detection not yet implemented.")
                    this.listener.onSetupStateChanged("Squat Detector Ready (Simulated)", true)
                    this.listener.onProgressUpdate(0, "Squats (Simulated)") // Initial count

                    // TODO: In a real implementation, you would start image analysis here.
                    // For now, we just show the preview and a message.

                } catch (exc: Exception) {
                    Log.e(TAG, "Use case binding failed for SquatDetector", exc)
                    this.listener.onError("Camera setup failed for Squats.", exc.message)
                    this.listener.onSetupStateChanged("Squat Detector: Camera Error", false)
                }
            }, ContextCompat.getMainExecutor(context)) // Run on main thread for UI context

        } else {
            Log.w(TAG, "Camera permission missing for SquatDetector.")
            this.listener.onPermissionMissing(getRequiredPermissions())
            this.listener.onSetupStateChanged("Squat Detector: Permission Missing", false)
        }
    }

    override fun stopTracking() {
        if (isTrackingActive) {
            Log.d(TAG, "Stopping squat tracking (simulated).")
            isTrackingActive = false
            try {
                cameraProvider?.unbindAll() // Unbind all use cases
                Log.d(TAG, "Camera resources unbound for SquatDetector.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera resources for SquatDetector: ${e.message}", e)
            }
        }
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
            Log.d(TAG, "CameraExecutor shutdown for SquatDetector.")
        }
        cameraProvider = null // Release camera provider
        cameraProviderFuture = null
        previewViewProvider = null // Clear the provider
        Log.d(TAG, "SquatDetector stopped.")
    }

    override fun isTracking(): Boolean {
        return isTrackingActive
    }
}