package com.example.pushuppatrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Calendar

class DailyBonusManager(private val context: Context) { // Made context a property

    companion object {
        private const val PREFS_NAME = "BonusTimePrefs"
        private const val KEY_LAST_BONUS_TIME_AWARDED_TIMESTAMP = "last_bonus_time_awarded_timestamp"
        const val BONUS_TIME_AWARD_SECONDS = 30L // The amount of time to award
        private const val TAG = "BonusTimeManager"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Checks if the daily bonus time time can be awarded.
     * It can be awarded if it hasn't been awarded today.
     */
    fun canAwardBonusTimeToday(): Boolean {
        val lastAwardedTimestamp = sharedPreferences.getLong(KEY_LAST_BONUS_TIME_AWARDED_TIMESTAMP, 0L)
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
        Log.d(TAG, "canAwardBonusTimeToday: $canAward (Last awarded: $lastAwardedTimestamp)")
        return canAward
    }

    /**
     * Awards the bonus time time by adding it to the TimeBankManager
     * and records that it has been used for today.
     * @return True if the bonus time was successfully awarded, false otherwise (e.g., already used today).
     */
    fun awardBonusTime(): Boolean {
        if (canAwardBonusTimeToday()) {
            val timeBankManager = TimeBankManager(context) // Get instance of TimeBankManager
            timeBankManager.addTimeSeconds(BONUS_TIME_AWARD_SECONDS.toInt()) // Add the time

            val currentTime = System.currentTimeMillis()
            sharedPreferences.edit().apply {
                putLong(KEY_LAST_BONUS_TIME_AWARDED_TIMESTAMP, currentTime)
                apply()
            }
            Log.i(TAG, "${BONUS_TIME_AWARD_SECONDS}s bonus time awarded and added to TimeBank. New total: ${timeBankManager.getTimeSeconds()}s")
            return true
        }
        Log.w(TAG, "Bonus time NOT awarded, already used today.")
        return false
    }

    /**
     * No longer needed as bonus time directly adds to time bank.
     * The AppBlockerService will rely on TimeBankManager.
     */
    // fun isBonusPeriodActive(): Boolean { return false } // Or remove completely

    /**
     * Resets the bonus time awarded state (for testing purposes).
     */
    fun resetBonusTimeAwardState() {
        sharedPreferences.edit().clear().apply()
        Log.d(TAG, "Bonus time award state has been reset.")
    }
}