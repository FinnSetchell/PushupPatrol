package com.example.pushuppatrol.features.activitytracking

import com.google.mlkit.vision.pose.Pose

// Or your chosen package

/**
 * Interface for receiving updates from a [TrackableActivity].
 * Implemented by the UI layer (e.g., an Activity or ViewModel) to display progress
 * and handle results or errors.
 */
interface ActivityProgressListener {

    /**
     * Called frequently to update the UI with the current progress of the activity.
     *
     * @param count The current count or accumulated value (e.g., number of push-ups, seconds elapsed).
     * @param currentUnitName The name of the unit being reported (e.g., "reps", "seconds").
     */
    fun onProgressUpdate(count: Int, currentUnitName: String)

    /**
     * Called when the activity is considered completed, either by the user manually stopping it
     * or by the activity reaching a predefined goal (if applicable).
     *
     * @param earnedUnits The total number of units successfully completed/earned during the session.
     * @param finalUnitName The name of the unit for the earned quantity.
     */
    fun onActivityCompleted(earnedUnits: Int, finalUnitName: String)

    /**
     * Called when an error occurs during activity tracking.
     *
     * @param errorMessage A user-friendly message describing the error.
     * @param errorDetails Optional detailed information about the error, useful for logging or debugging.
     */
    fun onError(errorMessage: String, errorDetails: String? = null)

    /**
     * Called by the [TrackableActivity] if [TrackableActivity.startTracking] is invoked
     * but one or more required permissions (as declared by [TrackableActivity.getRequiredPermissions])
     * have not been granted.
     *
     * @param missingPermissions An [Array] of permission strings that are required but not granted.
     */
    fun onPermissionMissing(missingPermissions: Array<String>)

    /**
     * (Optional) Called to inform the UI about changes in the setup or calibration state
     * of the activity, before actual tracking begins or if it's interrupted.
     *
     * @param message A descriptive message about the current setup state.
     * @param isReady Indicates whether the activity is now ready to begin active tracking.
     */
    fun onSetupStateChanged(message: String, isReady: Boolean)
}

interface PoseUpdateListener { // Could be combined with ActivityProgressListener
    fun onPoseDetected(pose: Pose, imageWidth: Int, imageHeight: Int, isFrontCamera: Boolean)
    fun onClearPose() // To clear the overlay when no pose is detected or tracking stops
}