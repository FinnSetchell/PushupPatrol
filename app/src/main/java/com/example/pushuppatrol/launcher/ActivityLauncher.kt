package com.example.pushuppatrol.launcher

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.util.Log
import com.example.pushuppatrol.PushupActivity
import com.example.pushuppatrol.activitytracking.ActivityType

object ActivityLauncher {

    fun launchActivity(context: Context, activityType: ActivityType) {
        val intent: Intent? = when (activityType) {
            ActivityType.PUSHUPS -> {
                Intent(context, PushupActivity::class.java)
                // Example of how you might pass the type if PushupActivity needed it
                // or if you were using a GenericExerciseActivity
                // intent.putExtra("EXTRA_ACTIVITY_TYPE_NAME", activityType.name)
            }
            ActivityType.SQUATS -> {
                Log.w("ActivityLauncher", "Squats activity not yet implemented.")
                Toast.makeText(context, "Squats feature is coming soon!", Toast.LENGTH_SHORT).show()
                null // Return null to indicate no activity to launch
            }
            ActivityType.STEPS -> {
                Log.w("ActivityLauncher", "Steps activity not yet implemented.")
                Toast.makeText(context, "Steps feature is coming soon!", Toast.LENGTH_SHORT).show()
                null // Return null
            }
        }

        intent?.let {
            context.startActivity(it)
        }
    }
}