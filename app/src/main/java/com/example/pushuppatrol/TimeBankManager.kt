package com.example.pushuppatrol // Replace with your actual package name

import android.content.Context
import android.content.SharedPreferences

class TimeBankManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "PushupPatrolPrefs"
        private const val KEY_TIME_BANK_SECONDS = "timeBankSeconds"
        private const val PUSHUP_TO_MINUTES_CONVERSION = 1 // 1 push-up = 1 minute
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Adds time to the bank based on the number of push-ups.
     * Each push-up earns a predefined number of minutes.
     * @param pushupCount The number of push-ups completed.
     */
    fun addPushups(pushupCount: Int) {
        if (pushupCount <= 0) return
        val minutesEarned = pushupCount * PUSHUP_TO_MINUTES_CONVERSION
        val secondsEarned = minutesEarned * 60
        val currentSeconds = getTimeSeconds()
        val newTotalSeconds = currentSeconds + secondsEarned
        sharedPreferences.edit().putLong(KEY_TIME_BANK_SECONDS, newTotalSeconds.toLong()).apply()
    }

    /**
     * Directly adds a specified number of seconds to the time bank.
     * Useful for features like the "Snooze" button.
     * @param seconds The number of seconds to add.
     */
    fun addTimeSeconds(seconds: Int) {
        if (seconds <= 0) return
        val currentSeconds = getTimeSeconds()
        val newTotalSeconds = currentSeconds + seconds
        sharedPreferences.edit().putLong(KEY_TIME_BANK_SECONDS, newTotalSeconds.toLong()).apply()
    }


    /**
     * Gets the current time available in the bank, in seconds.
     * @return Total seconds available.
     */
    fun getTimeSeconds(): Int {
        // Use getLong and convert to Int, defaulting to 0L if not found
        return sharedPreferences.getLong(KEY_TIME_BANK_SECONDS, 0L).toInt()
    }

    /**
     * Consumes a specified number of seconds from the time bank.
     * @param secondsToUse The number of seconds to consume.
     * @return True if time was successfully used, false if not enough time or invalid input.
     */
    fun useTime(secondsToUse: Int): Boolean {
        if (secondsToUse <= 0) return false // Cannot use zero or negative time

        val currentSeconds = getTimeSeconds()
        if (currentSeconds >= secondsToUse) {
            val remainingSeconds = currentSeconds - secondsToUse
            sharedPreferences.edit().putLong(KEY_TIME_BANK_SECONDS, remainingSeconds.toLong()).apply()
            return true
        }
        return false // Not enough time
    }

    /**
     * Clears the entire time bank. Useful for testing or reset.
     */
    fun clearTimeBank() {
        sharedPreferences.edit().remove(KEY_TIME_BANK_SECONDS).apply()
    }
}