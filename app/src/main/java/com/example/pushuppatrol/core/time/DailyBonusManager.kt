package com.example.pushuppatrol.core.time

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager // For accessing default SharedPreferences
import java.util.Calendar

class DailyBonusManager(private val context: Context) {

    companion object {
        // This PREFS_NAME is for storing the *timestamp* of the last bonus award.
        // The actual settings (enable/disable, amount) are stored in default SharedPreferences.
        private const val FEATURE_STATE_PREFS_NAME = "DailyBonusFeatureStatePrefs"
        private const val KEY_LAST_BONUS_TIME_AWARDED_TIMESTAMP = "last_bonus_time_awarded_timestamp"

        // Default value if preference not set or accessible
        const val DEFAULT_BONUS_AWARD_SECONDS = 30L // Your existing default from previous version

        // Preference Keys (these MUST match the keys you will define/use in your SettingsActivity)
        const val PREF_KEY_ENABLE_DAILY_BONUS = "pref_enable_daily_bonus"
        const val PREF_KEY_DAILY_BONUS_AMOUNT_SECONDS = "pref_daily_bonus_amount_seconds"

        private const val TAG = "DailyBonusManager" // Or your "BonusTimeManager"
    }

    // SharedPreferences for storing the last awarded timestamp (specific to this feature's state)
    private val featureStatePreferences: SharedPreferences =
        context.getSharedPreferences(FEATURE_STATE_PREFS_NAME, Context.MODE_PRIVATE)

    // Default SharedPreferences where settings (enable/disable, amount) are stored
    private val settingsPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * Gets the configured daily bonus amount in seconds from settings.
     * Defaults to DEFAULT_BONUS_AWARD_SECONDS if the preference is not found or not a valid number.
     */
    fun getBonusAmountSeconds(): Long {
        return settingsPreferences.getString(
            PREF_KEY_DAILY_BONUS_AMOUNT_SECONDS,
            DEFAULT_BONUS_AWARD_SECONDS.toString()
        )?.toLongOrNull() ?: DEFAULT_BONUS_AWARD_SECONDS
    }

    /**
     * Checks if the Daily Bonus feature is enabled in settings.
     * Defaults to true if the preference is not found.
     */
    fun isFeatureEnabled(): Boolean {
        return settingsPreferences.getBoolean(PREF_KEY_ENABLE_DAILY_BONUS, true) // Default to true
    }

    /**
     * Checks if the daily bonus time can be awarded.
     * It can be awarded if the feature is enabled in settings AND it hasn't been awarded today.
     */
    fun canAwardBonusTimeToday(): Boolean {
        if (!isFeatureEnabled()) {
            Log.d(TAG, "Daily Bonus feature is disabled in settings.")
            return false
        }

        val lastAwardedTimestamp =
            featureStatePreferences.getLong(KEY_LAST_BONUS_TIME_AWARDED_TIMESTAMP, 0L)
        if (lastAwardedTimestamp == 0L) {
            Log.d(TAG, "Daily bonus never awarded before or timestamp reset.")
            return true // Never awarded before
        }

        val todayCalendar = Calendar.getInstance()
        val lastAwardedCalendar = Calendar.getInstance().apply {
            timeInMillis = lastAwardedTimestamp
        }

        // Compare year and day of year
        val canAward = todayCalendar.get(Calendar.YEAR) > lastAwardedCalendar.get(Calendar.YEAR) ||
                (todayCalendar.get(Calendar.YEAR) == lastAwardedCalendar.get(Calendar.YEAR) &&
                        todayCalendar.get(Calendar.DAY_OF_YEAR) > lastAwardedCalendar.get(Calendar.DAY_OF_YEAR))

        if (canAward) {
            Log.d(TAG, "Can award daily bonus. Last awarded on a different day.")
        } else {
            Log.d(TAG, "Cannot award daily bonus. Already awarded today. Last awarded timestamp: $lastAwardedTimestamp")
        }
        return canAward
    }

    /**
     * Awards the bonus time by adding it to the TimeBankManager
     * and records that it has been used for today.
     * The amount awarded is read from settings.
     * @return True if the bonus time was successfully awarded, false otherwise.
     */
    fun awardBonusTime(): Boolean {
        // canAwardBonusTimeToday() already checks if the feature is enabled.
        if (canAwardBonusTimeToday()) {
            val timeBankManager = TimeBankManager(context)
            val bonusAmountToAward = getBonusAmountSeconds() // Get configured amount

            timeBankManager.addTimeSeconds(bonusAmountToAward.toInt())

            val currentTime = System.currentTimeMillis()
            featureStatePreferences.edit().apply {
                putLong(KEY_LAST_BONUS_TIME_AWARDED_TIMESTAMP, currentTime)
                apply()
            }
            Log.i(TAG, "$bonusAmountToAward bonus seconds awarded (from settings) and added to TimeBank. New total in bank: ${timeBankManager.getTimeSeconds()}s")
            return true
        }
        Log.w(TAG, "Bonus time NOT awarded. Conditions not met (feature disabled or already used today).")
        return false
    }

    /**
     * Resets the bonus time awarded state (for testing purposes) by clearing the timestamp.
     */
    fun resetBonusTimeAwardState() {
        featureStatePreferences.edit().remove(KEY_LAST_BONUS_TIME_AWARDED_TIMESTAMP).apply()
        Log.d(TAG, "Bonus time award state (timestamp) has been reset.")
    }
}
