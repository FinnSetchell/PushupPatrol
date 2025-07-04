package com.example.pushuppatrol.core.time

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.pushuppatrol.features.activitytracking.ActivityType // Ensure this import is present

class TimeBankManager(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "TimeBankPrefs"
        private const val KEY_TIME_SECONDS = "time_seconds"
        private const val KEY_SECONDS_PER_PUSHUP = "seconds_per_pushup"
        private const val DEFAULT_SECONDS_PER_PUSHUP = 60
        private const val TAG = "TimeBankManager"

        // New preference key for default activity type
        private const val PREF_KEY_DEFAULT_ACTIVITY_TYPE = "default_activity_type"
    }

    // ... (existing methods: addTimeSeconds, useTime, getTimeSeconds, hasTime, clearTimeBank) ...

    fun addPushups(pushupCount: Int) {
        val secondsPerPushup = getSecondsPerPushup()
        val timeToAdd = pushupCount * secondsPerPushup
        addTimeSeconds(timeToAdd)
        Log.d(TAG, "$pushupCount push-ups added $timeToAdd seconds. Seconds per push-up: $secondsPerPushup. New total: ${getTimeSeconds()}s")
    }
    fun addPushups(pushupCount: Int, secondsPerPushup: Int) { // Overloaded for flexibility if needed elsewhere
        val timeToAdd = pushupCount * secondsPerPushup
        addTimeSeconds(timeToAdd)
        Log.d(TAG, "$pushupCount push-ups added $timeToAdd seconds (using $secondsPerPushup s/push-up). New total: ${getTimeSeconds()}s")
    }


    fun setSecondsPerPushup(seconds: Int) {
        sharedPreferences.edit {
            putInt(KEY_SECONDS_PER_PUSHUP, seconds)
        }
        Log.d(TAG, "Seconds per push-up set to: $seconds")
    }

    fun getSecondsPerPushup(): Int {
        return sharedPreferences.getInt(KEY_SECONDS_PER_PUSHUP, DEFAULT_SECONDS_PER_PUSHUP)
    }

    // --- New Methods for Default Activity Type ---
    fun setDefaultActivityType(activityType: ActivityType) {
        sharedPreferences.edit {
            putString(PREF_KEY_DEFAULT_ACTIVITY_TYPE, activityType.name) // Save enum by its name
        }
        Log.d(TAG, "Default activity type preference set to: ${activityType.name}")
    }

    fun getDefaultActivityType(): ActivityType {
        // Use PUSHUPS.name as the default string if nothing is found
        val typeName = sharedPreferences.getString(PREF_KEY_DEFAULT_ACTIVITY_TYPE, ActivityType.PUSHUPS.name)
        return try {
            ActivityType.valueOf(typeName ?: ActivityType.PUSHUPS.name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid saved activity type '$typeName', defaulting to PUSHUPS.", e)
            ActivityType.PUSHUPS // Fallback if the saved string is somehow invalid
        }
    }
    // --- End New Methods ---

    // Existing methods from your snippet (ensure they are here)
    fun addTimeSeconds(seconds: Int) {
        val currentTotal = getTimeSeconds()
        val newTotal = currentTotal + seconds
        sharedPreferences.edit {
            putInt(KEY_TIME_SECONDS, newTotal)
        }
        Log.d(TAG, "Added $seconds seconds. Old total: $currentTotal. New total: $newTotal")
    }

    fun useTime(secondsToUse: Int): Boolean {
        val currentTotal = getTimeSeconds()
        if (currentTotal >= secondsToUse) {
            val newTotal = currentTotal - secondsToUse
            sharedPreferences.edit {
                putInt(KEY_TIME_SECONDS, newTotal)
            }
            Log.d(TAG, "Used $secondsToUse seconds. Old total: $currentTotal. New total: $newTotal")
            return true
        }
        Log.d(TAG, "Not enough time to use $secondsToUse seconds. Current total: $currentTotal")
        return false
    }

    fun getTimeSeconds(): Int {
        return sharedPreferences.getInt(KEY_TIME_SECONDS, 0)
    }

    fun hasTime(): Boolean {
        return getTimeSeconds() > 0
    }

    fun clearTimeBank() {
        sharedPreferences.edit {
            putInt(KEY_TIME_SECONDS, 0)
            // Optionally reset other settings here too if desired
            // putInt(KEY_SECONDS_PER_PUSHUP, DEFAULT_SECONDS_PER_PUSHUP)
            // putString(PREF_KEY_DEFAULT_ACTIVITY_TYPE, ActivityType.PUSHUPS.name)
        }
        Log.d(TAG, "Time bank cleared.")
    }
}