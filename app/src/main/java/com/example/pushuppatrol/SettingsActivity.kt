package com.example.pushuppatrol // Replace with your package

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter // Import ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.pushuppatrol.activitytracking.ActivityType // Import ActivityType
import com.example.pushuppatrol.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var timeBankManager: TimeBankManager

    companion object {
        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        timeBankManager = TimeBankManager(applicationContext)

        setSupportActionBar(binding.toolbarSettings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarSettings.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        loadAndDisplaySettings()
        setupActivityTypeSpinner() // New method call

        binding.btnSaveChanges.setOnClickListener {
            saveSecondsPerPushupSetting()
            Toast.makeText(this, "Settings applied!", Toast.LENGTH_SHORT).show()

            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            var view = currentFocus
            // If no view currently has focus, create a new one, just so we can grab a window token from it
            if (view == null) {
                view = View(this)
            }
            imm.hideSoftInputFromWindow(view.windowToken, 0) // Use view.windowToken
        }
    }

    private fun loadAndDisplaySettings() {
        val currentSecondsPerPushup = timeBankManager.getSecondsPerPushup()
        binding.etSecondsPerPushup.setText(currentSecondsPerPushup.toString())
        Log.d(TAG, "Loaded seconds per pushup: $currentSecondsPerPushup")
    }

    private fun setupActivityTypeSpinner() {
        // Create an ArrayAdapter using the ActivityType enum values
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, // Standard layout for the selected item view
            ActivityType.values() // Gets all enum constants, uses their toString() for display
        ).also {
            // Specify the layout to use when the list of choices appears
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        binding.spinnerActivityType.adapter = adapter

        // Load saved preference and set Spinner selection
        val savedActivityType = timeBankManager.getDefaultActivityType()
        binding.spinnerActivityType.setSelection(savedActivityType.ordinal)
        Log.d(TAG, "Loaded default activity type for spinner: $savedActivityType")


        binding.spinnerActivityType.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selectedType = parent?.getItemAtPosition(position) as ActivityType
                    timeBankManager.setDefaultActivityType(selectedType)
                    Log.d(TAG, "Default activity type saved: $selectedType")
                    // Optionally, show a toast that this specific setting was saved immediately
                    // Toast.makeText(applicationContext, "Default activity set to ${selectedType.toString()}", Toast.LENGTH_SHORT).show()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // Interface method; can be left empty if not needed
                }
            }
    }

    private fun saveSecondsPerPushupSetting() {
        val secondsText = binding.etSecondsPerPushup.text.toString()
        if (secondsText.isNotEmpty()) {
            try {
                val seconds = secondsText.toInt()
                if (seconds > 0 && seconds < 1000) {
                    timeBankManager.setSecondsPerPushup(seconds)
                    Log.d(TAG, "Saved seconds per pushup: $seconds")
                    // Toast for this specific save is optional if btnSaveChanges gives overall feedback
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