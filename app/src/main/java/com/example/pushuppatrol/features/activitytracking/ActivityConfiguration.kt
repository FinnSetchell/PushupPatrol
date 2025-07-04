package com.example.pushuppatrol.features.activitytracking // Or your chosen package

/**
 * Base class for activity-specific configurations.
 * Implementations of [TrackableActivity] can expect a subclass of this type
 * if they require specific parameters to be passed during initialization via
 * [TrackableActivity.startTracking].
 */
open class ActivityConfiguration