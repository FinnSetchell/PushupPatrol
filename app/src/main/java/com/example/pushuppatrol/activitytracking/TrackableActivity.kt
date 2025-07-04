package com.example.pushuppatrol.activitytracking // Or your chosen package

import android.content.Context
// import android.Manifest // Not strictly needed here, but good for context

/**
 * Interface for a physical activity that can be tracked to earn rewards.
 * Defines common behaviors, properties, and how an activity is controlled.
 */
interface TrackableActivity {

    /**
     * Starts tracking the physical activity.
     *
     * This method should initialize any necessary resources (e.g., camera, sensors, ML models)
     * and begin the tracking process. Progress and results are communicated via the [listener].
     *
     * @param context The Android [Context] for accessing resources or system services.
     * @param listener The [ActivityProgressListener] to receive updates on activity progress and completion.
     * @param config Optional [ActivityConfiguration] for activity-specific parameters.
     */
    fun startTracking(
        context: Context,
        listener: ActivityProgressListener,
        config: ActivityConfiguration? = null
    )

    /**
     * Stops the current activity tracking.
     *
     * This method should release all resources acquired during [startTracking]
     * (e.g., camera, sensors, ML models, executors). It's crucial to call this
     * when the activity is paused, stopped, or destroyed to prevent resource leaks.
     */
    fun stopTracking()

    /**
     * Returns a list of Android permissions required for this activity to function.
     * For example, `Manifest.permission.CAMERA` for activities using the camera.
     *
     * @return An [Array] of permission strings. Returns an empty array if no special permissions are needed.
     */
    fun getRequiredPermissions(): Array<String>

    /**
     * Provides a user-friendly display name for the activity.
     * This should be sourced from string resources for localization.
     *
     * @param context The Android [Context] for accessing string resources.
     * @return The localized display name of the activity (e.g., "Push-ups", "Squats").
     */
    fun getDisplayName(context: Context): String

    /**
     * Provides the name of the unit being tracked for this activity.
     * This should be sourced from string resources for localization.
     *
     * @param context The Android [Context] for accessing string resources.
     * @return The localized name of the unit (e.g., "reps", "seconds", "steps").
     */
    fun getUnitName(context: Context): String
}