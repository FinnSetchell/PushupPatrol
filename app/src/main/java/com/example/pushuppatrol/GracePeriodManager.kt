package com.example.pushuppatrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Calendar

class GracePeriodManager(private val context: Context) { // Made context a property

    companion object {
        private const val PREFS_NAME = "GracePeriodPrefs"
        private const val KEY_LAST_GRACE_PERIOD_AWARDED_TIMESTAMP = "last_grace_period_awarded_timestamp"
        const val GRACE_PERIOD_AWARD_SECONDS = 30L // The amount of time to award
        private const val TAG = "GracePeriodManager"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Checks if the daily grace period time can be awarded.
     * It can be awarded if it hasn't been awarded today.
     */
    fun canAwardGraceTimeToday(): Boolean {
        val lastAwardedTimestamp = sharedPreferences.getLong(KEY_LAST_GRACE_PERIOD_AWARDED_TIMESTAMP, 0L)
        if (lastAwardedTimestamp == 0L) {
            return true // Never awarded before
        }

        val todayCalendar = Calendar.getInstance()
        val lastAwardedCalendar = Calendar.getInstance().apply {
            timeInMillis = lastAwardedTimestamp
        }

        // Compare year and day of year
        val canAward = todayCalendar.get(Calendar.YEAR) > lastAwardedCalendar.get(Calendar.YEAR) ||
                todayCalendar.get(Calendar.DAY_OF_YEAR) > lastAwardedCalendar.get(Calendar.DAY_OF_YEAR)
        Log.d(TAG, "canAwardGraceTimeToday: $canAward (Last awarded: $lastAwardedTimestamp)")
        return canAward
    }

    /**
     * Awards the grace period time by adding it to the TimeBankManager
     * and records that it has been used for today.
     * @return True if the grace time was successfully awarded, false otherwise (e.g., already used today).
     */
    fun awardGraceTime(): Boolean {
        if (canAwardGraceTimeToday()) {
            val timeBankManager = TimeBankManager(context) // Get instance of TimeBankManager
            timeBankManager.addTimeSeconds(GRACE_PERIOD_AWARD_SECONDS.toInt()) // Add the time

            val currentTime = System.currentTimeMillis()
            sharedPreferences.edit().apply {
                putLong(KEY_LAST_GRACE_PERIOD_AWARDED_TIMESTAMP, currentTime)
                apply()
            }
            Log.i(TAG, "${GRACE_PERIOD_AWARD_SECONDS}s grace time awarded and added to TimeBank. New total: ${timeBankManager.getTimeSeconds()}s")
            return true
        }
        Log.w(TAG, "Grace time NOT awarded, already used today.")
        return false
    }

    /**
     * No longer needed as grace period directly adds to time bank.
     * The AppBlockerService will rely on TimeBankManager.
     */
    // fun isGracePeriodActive(): Boolean { return false } // Or remove completely

    /**
     * Resets the grace period awarded state (for testing purposes).
     */
    fun resetGracePeriodAwardState() {
        sharedPreferences.edit().clear().apply()
        Log.d(TAG, "Grace period award state has been reset.")
    }
}