package com.example.pushuppatrol.features.activitytracking

/**
 * Represents the different types of physical activities users can perform.
 */
enum class ActivityType {
    PUSHUPS,
    SQUATS,  // Future activity
    STEPS;   // Future activity (could be from phone sensors)

    // Optional: To make them more human-readable for the Spinner
    override fun toString(): String {
        return when (this) {
            PUSHUPS -> "Push-ups"
            SQUATS -> "Squats (Not Implemented)"
            STEPS -> "Steps (Not Implemented)"
        }
    }
}