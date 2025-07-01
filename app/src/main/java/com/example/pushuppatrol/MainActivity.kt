package com.example.pushuppatrol // Replace with your package name

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tempButton: Button = findViewById(R.id.tempLaunchPushupActivityButton)
        tempButton.setOnClickListener {
            startActivity(Intent(this, PushupActivity::class.java))
        }
    }
}