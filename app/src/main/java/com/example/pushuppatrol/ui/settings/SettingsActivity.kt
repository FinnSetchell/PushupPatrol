package com.example.pushuppatrol.ui.settings

import android.content.Context
import android.content.SharedPreferences // Import SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager // For default SharedPreferences
import com.example.pushuppatrol.R
import com.example.pushuppatrol.features.activitytracking.ActivityType
import com.example.pushuppatrol.core.time.DailyBonusManager
import com.example.pushuppatrol.core.time.TimeBankManager
import com.example.pushuppatrol.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var timeBankManager: TimeBankManager
    private lateinit var sharedPreferences: SharedPreferences // For app settings

    // Companion object to access preference keys from DailyBonusManager
    companion object {
        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        timeBankManager = TimeBankManager(applicationContext)
        // Initialize SharedPreferences for reading/writing settings
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)


        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarSettings.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        loadAndDisplayAllSettings() // Renamed for clarity
        setupActivityTypeSpinner()
        setupDailyBonusAmountSpinner() // New method call

        binding.btnSaveChanges.setOnClickListener {
            saveAllSettings() // Renamed for clarity
            Toast.makeText(this, "Settings applied!", Toast.LENGTH_SHORT).show()
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var view = currentFocus
        if (view == null) {
            view = View(this)
        }
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun loadAndDisplayAllSettings() {
        // Load Seconds Per Pushup
        val currentSecondsPerPushup = timeBankManager.getSecondsPerPushup()
        binding.etSecondsPerPushup.setText(currentSecondsPerPushup.toString())
        Log.d(TAG, "Loaded seconds per pushup: $currentSecondsPerPushup")

        // Load Daily Bonus Enabled state
        val dailyBonusEnabled = sharedPreferences.getBoolean(
            DailyBonusManager.PREF_KEY_ENABLE_DAILY_BONUS,
            true // Default to true if not found
        )
        binding.switchEnableDailyBonus.isChecked = dailyBonusEnabled
        binding.spinnerDailyBonusAmount.isEnabled = dailyBonusEnabled // Enable/disable spinner based on switch
        Log.d(TAG, "Loaded Daily Bonus Enabled: $dailyBonusEnabled")

        // Daily Bonus Amount will be loaded by its spinner setup
    }

    private fun setupActivityTypeSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ActivityType.values()
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerActivityType.adapter = adapter
        val savedActivityType = timeBankManager.getDefaultActivityType()
        binding.spinnerActivityType.setSelection(savedActivityType.ordinal)
        Log.d(TAG, "Loaded default activity type for spinner: $savedActivityType")

        // No need to save immediately here if btnSaveChanges handles it
        // If you want immediate save, keep the onItemSelectedListener
    }

    private fun setupDailyBonusAmountSpinner() {
        val bonusAmountEntries = resources.getStringArray(R.array.daily_bonus_amount_entries_settings)
        // The values array will be used to find the correct selection
        val bonusAmountValues = resources.getStringArray(R.array.daily_bonus_amount_values_settings)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            bonusAmountEntries // Display user-friendly entries
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerDailyBonusAmount.adapter = adapter

        // Load saved preference and set Spinner selection
        val savedBonusAmountStr = sharedPreferences.getString(
            DailyBonusManager.PREF_KEY_DAILY_BONUS_AMOUNT_SECONDS,
            DailyBonusManager.DEFAULT_BONUS_AWARD_SECONDS.toString() // Default from DailyBonusManager
        )
        val selectionIndex = bonusAmountValues.indexOf(savedBonusAmountStr)
        if (selectionIndex != -1) {
            binding.spinnerDailyBonusAmount.setSelection(selectionIndex)
        } else {
            // Fallback if saved value isn't in our list (e.g., set to default)
            val defaultIndex = bonusAmountValues.indexOf(DailyBonusManager.DEFAULT_BONUS_AWARD_SECONDS.toString())
            if (defaultIndex != -1) binding.spinnerDailyBonusAmount.setSelection(defaultIndex)
        }
        Log.d(TAG, "Loaded Daily Bonus Amount for spinner: $savedBonusAmountStr")

        // Listener for the Enable/Disable Switch to toggle the spinner
        binding.switchEnableDailyBonus.setOnCheckedChangeListener { _, isChecked ->
            binding.spinnerDailyBonusAmount.isEnabled = isChecked
            // If you want to save the enable/disable state immediately:
            // sharedPreferences.edit().putBoolean(DailyBonusManager.PREF_KEY_ENABLE_DAILY_BONUS, isChecked).apply()
            // Log.d(TAG, "Daily Bonus Enabled state immediate save: $isChecked")
        }
    }


    private fun saveAllSettings() {
        // Save Seconds Per Pushup
        saveSecondsPerPushupSetting() // Existing method

        // Save Daily Bonus Enabled state
        val dailyBonusEnabled = binding.switchEnableDailyBonus.isChecked
        sharedPreferences.edit()
            .putBoolean(DailyBonusManager.PREF_KEY_ENABLE_DAILY_BONUS, dailyBonusEnabled)
            .apply() // Apply immediately
        Log.d(TAG, "Saved Daily Bonus Enabled: $dailyBonusEnabled")

        // Save Daily Bonus Amount (if enabled)
        if (dailyBonusEnabled) {
            val selectedPosition = binding.spinnerDailyBonusAmount.selectedItemPosition
            val bonusAmountValues = resources.getStringArray(R.array.daily_bonus_amount_values_settings)
            if (selectedPosition >= 0 && selectedPosition < bonusAmountValues.size) {
                val selectedBonusAmount = bonusAmountValues[selectedPosition]
                sharedPreferences.edit()
                    .putString(DailyBonusManager.PREF_KEY_DAILY_BONUS_AMOUNT_SECONDS, selectedBonusAmount)
                    .apply() // Apply immediately
                Log.d(TAG, "Saved Daily Bonus Amount: $selectedBonusAmount seconds")
            }
        } else {
            // Optional: If feature is disabled, you could clear the amount or leave it as is.
            // For now, we'll leave it, so if re-enabled, the previous selection is remembered.
            Log.d(TAG, "Daily Bonus is disabled, amount not explicitly saved (retains previous value).")
        }


        // Save Default Activity Type (if you remove immediate save from its spinner)
        val selectedActivityType = binding.spinnerActivityType.selectedItem as ActivityType
        timeBankManager.setDefaultActivityType(selectedActivityType)
        Log.d(TAG, "Saved Default Activity Type: $selectedActivityType from Save All button")
    }


    private fun saveSecondsPerPushupSetting() { // Your existing method, no changes needed here
        val secondsText = binding.etSecondsPerPushup.text.toString()
        if (secondsText.isNotEmpty()) {
            try {
                val seconds = secondsText.toInt()
                if (seconds > 0 && seconds < 1000) { // Max 999
                    timeBankManager.setSecondsPerPushup(seconds)
                    Log.d(TAG, "Saved seconds per pushup: $seconds")
                } else {
                    Toast.makeText(this, "Please enter a value between 1 and 999 for seconds.", Toast.LENGTH_SHORT).show()
                    binding.etSecondsPerPushup.setText(timeBankManager.getSecondsPerPushup().toString())
                }
            } catch (e: NumberFormatException) {
                Toast.makeText(this, "Invalid number format for seconds.", Toast.LENGTH_SHORT).show()
                binding.etSecondsPerPushup.setText(timeBankManager.getSecondsPerPushup().toString())
            }
        } else {
            Toast.makeText(this, "Seconds per push-up cannot be empty.", Toast.LENGTH_SHORT).show()
            binding.etSecondsPerPushup.setText(timeBankManager.getSecondsPerPushup().toString())
        }
    }
}