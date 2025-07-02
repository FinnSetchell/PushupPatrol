package com.example.pushuppatrol

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    private lateinit var timeBankManager: TimeBankManager
    private lateinit var timeDisplayTextView: TextView // For displaying time

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        timeBankManager = TimeBankManager(applicationContext)

        val tempLaunchButton: Button = findViewById(R.id.tempLaunchPushupActivityButton)
        tempLaunchButton.setOnClickListener {
            startActivity(Intent(this, PushupActivity::class.java))
        }

        // --- Temporary Time Display ---
        timeDisplayTextView = findViewById(R.id.tempTimeDisplay) // Add this ID to activity_main.xml
        // --- End Temporary Time Display ---
    }

    override fun onResume() {
        super.onResume()
        // --- Temporary Time Display Update ---
        val totalSeconds = timeBankManager.getTimeSeconds()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        timeDisplayTextView.text = "Time Bank: ${minutes}m ${seconds}s"
        // --- End Temporary Time Display Update ---
    }
}