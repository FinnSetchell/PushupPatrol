package com.example.pushuppatrol.ui.blocking

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.pushuppatrol.core.time.DailyBonusManager
import com.example.pushuppatrol.ui.earning.PushupActivity
import com.example.pushuppatrol.R
import com.example.pushuppatrol.databinding.ActivityInterstitialBlockBinding
import com.example.pushuppatrol.ui.main.MainActivity

class InterstitialBlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInterstitialBlockBinding
    private lateinit var dailyBonusManager: DailyBonusManager

    private var blockedAppPackageName: String? = null
    private var blockedAppDisplayName: String? = null

    companion object {
        const val EXTRA_BLOCKED_APP_PACKAGE_NAME = "com.example.pushuppatrol.BLOCKED_APP_PACKAGE_NAME"
        const val EXTRA_BLOCKED_APP_DISPLAY_NAME = "com.example.pushuppatrol.BLOCKED_APP_DISPLAY_NAME"
        private const val TAG = "InterstitialActivity"
        private const val UI_FEEDBACK_DELAY_MS = 1500L // 1.5 seconds for feedback
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInterstitialBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dailyBonusManager = DailyBonusManager(applicationContext)

        blockedAppPackageName = intent.getStringExtra(EXTRA_BLOCKED_APP_PACKAGE_NAME)
        blockedAppDisplayName = intent.getStringExtra(EXTRA_BLOCKED_APP_DISPLAY_NAME) ?: getAppNameFromPackage(blockedAppPackageName)

        if (blockedAppPackageName == null) {
            Log.e(TAG, "Blocked app package name not provided. Finishing activity.")
            Toast.makeText(this, "Error: Blocked app info missing.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUI()
        setupListeners()
        setupOnBackPressed()
    }

    private fun getAppNameFromPackage(packageName: String?): String {
        if (packageName == null) return "Selected App"
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "App name not found for package: $packageName", e)
            packageName
        }
    }

    private fun getAppIconFromPackage(packageName: String?): Drawable? {
        if (packageName == null) return null
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "App icon not found for package: $packageName", e)
            null
        }
    }

    private fun setupUI() {
        val appName = blockedAppDisplayName ?: "Selected App"
        binding.tvInterstitialTitle.text = getString(R.string.interstitial_title_placeholder, appName)
        binding.ivBlockedAppIcon.setImageDrawable(getAppIconFromPackage(blockedAppPackageName))

        updateBonusTimeButtonState() // This will now check if the feature is enabled

        binding.btnDevResetDailyBonus.visibility = if (isDebugBuild()) View.VISIBLE else View.GONE
    }

    private fun updateBonusTimeButtonState() {
        // First, check if the entire feature is enabled in settings
        if (!dailyBonusManager.isFeatureEnabled()) {
            binding.btnClaimDailyBonus.visibility = View.GONE
            binding.tvDailyBonusInfo.visibility = View.GONE
            Log.d(TAG, "Daily Bonus feature is disabled via settings. Hiding UI elements.")
            return // Don't proceed to update text if feature is off
        }

        // Feature is enabled, so make UI visible and update text
        binding.btnClaimDailyBonus.visibility = View.VISIBLE
        binding.tvDailyBonusInfo.visibility = View.VISIBLE

        val configuredBonusAmount = dailyBonusManager.getBonusAmountSeconds()

        if (dailyBonusManager.canAwardBonusTimeToday()) {
            binding.btnClaimDailyBonus.isEnabled = true
            // Use the string that takes a parameter for seconds
            binding.btnClaimDailyBonus.text = getString(R.string.interstitial_button_get_bonus_time, configuredBonusAmount)
            binding.tvDailyBonusInfo.text = getString(R.string.bonus_time_available_info)
        } else {
            binding.btnClaimDailyBonus.isEnabled = false
            // Use the string that takes a parameter, showing the amount that *was* used/available
            binding.btnClaimDailyBonus.text = getString(R.string.interstitial_button_get_bonus_time_used, configuredBonusAmount)
            binding.tvDailyBonusInfo.text = getString(R.string.bonus_time_used_info)
        }
    }

    private fun setupListeners() {
        binding.btnClaimDailyBonus.setOnClickListener {
            // awardBonusTime() will use the configured amount and also checks if it can be awarded
            if (dailyBonusManager.awardBonusTime()) {
                val awardedAmount = dailyBonusManager.getBonusAmountSeconds() // Get the amount that was just awarded

                // UI Feedback
                binding.tvInterstitialMessage.text = getString(R.string.bonus_time_awarded_toast, awardedAmount)
                // Temporarily disable/hide buttons during feedback
                binding.btnClaimDailyBonus.visibility = View.GONE
                binding.tvDailyBonusInfo.visibility = View.GONE
                binding.btnEarnTime.isEnabled = false
                binding.btnTakeBreak.isEnabled = false


                Log.i(TAG, "$awardedAmount bonus seconds awarded for $blockedAppPackageName. Displaying feedback then finishing.")

                Handler(Looper.getMainLooper()).postDelayed({
                    finishAffinity() // Close this task, user returns to the blocked app, service re-evaluates
                }, UI_FEEDBACK_DELAY_MS)
            } else {
                // This case should be rare if UI is updated correctly, but handle it.
                // Could happen if user clicks rapidly or settings change in background.
                Toast.makeText(this, R.string.bonus_time_unavailable_toast, Toast.LENGTH_SHORT).show()
                updateBonusTimeButtonState() // Re-sync UI
            }
        }

        binding.btnEarnTime.setOnClickListener {
            // If you want to prevent clicking this during the feedback delay, check a flag or disable it.
            Log.i(TAG, "Earn Time button clicked for $blockedAppPackageName. Launching PushupActivity.")
            val intent = Intent(this, PushupActivity::class.java).apply {
                putExtra(EXTRA_BLOCKED_APP_PACKAGE_NAME, blockedAppPackageName)
            }
            startActivity(intent)
            finishAffinity()
        }

        binding.btnTakeBreak.setOnClickListener {
            // If you want to prevent clicking this during the feedback delay, check a flag or disable it.
            Log.i(TAG, "Take a Break button clicked. Navigating to home screen.")
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(homeIntent)
            finishAffinity()
        }

        binding.btnDevResetDailyBonus.setOnClickListener {
            dailyBonusManager.resetBonusTimeAwardState()
            // Make sure the toast for dev reset is clear, maybe "DEV: Daily Bonus Reset"
            Toast.makeText(this, "DEV: Daily Bonus state reset!", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Developer action: Daily bonus award state reset.")
            updateBonusTimeButtonState() // Re-sync UI
        }
    }

    private fun setupOnBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back press detected. Navigating to MainActivity.")
                val mainActivityIntent = Intent(this@InterstitialBlockActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(mainActivityIntent)
                finish()
            }
        })
    }

    private fun isDebugBuild(): Boolean {
        return 0 != (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
    }
}