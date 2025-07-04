package com.example.pushuppatrol

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.pushuppatrol.databinding.ActivityInterstitialBlockBinding

class InterstitialBlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInterstitialBlockBinding
    private lateinit var dailyBonusManager: DailyBonusManager
    // private lateinit var timeBankManager: TimeBankManager // Not strictly needed here anymore, but could be useful for display

    private var blockedAppPackageName: String? = null
    private var blockedAppDisplayName: String? = null

    companion object {
        const val EXTRA_BLOCKED_APP_PACKAGE_NAME = "com.example.pushuppatrol.BLOCKED_APP_PACKAGE_NAME"
        const val EXTRA_BLOCKED_APP_DISPLAY_NAME = "com.example.pushuppatrol.BLOCKED_APP_DISPLAY_NAME"
        private const val TAG = "InterstitialActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInterstitialBlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dailyBonusManager = DailyBonusManager(applicationContext)
        // timeBankManager = TimeBankManager(applicationContext) // Optional: if you want to display current time

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

        updateBonusTimeButtonState()

        if (isDebugBuild()) {
            binding.btnDevResetDailyBonus.visibility = View.VISIBLE
        } else {
            binding.btnDevResetDailyBonus.visibility = View.GONE
        }
        // Optionally, display current time in bank:
        // binding.tvSomeTimeBankDisplay.text = "Current time: ${timeBankManager.getTimeSeconds()}s"
    }

    private fun updateBonusTimeButtonState() {
        if (dailyBonusManager.canAwardBonusTimeToday()) {
            binding.btnClaimDailyBonus.isEnabled = true
            binding.btnClaimDailyBonus.text = getString(R.string.interstitial_button_get_bonus_time, DailyBonusManager.BONUS_TIME_AWARD_SECONDS)
            binding.tvDailyBonusInfo.text = getString(R.string.bonus_time_available_info)
        } else {
            binding.btnClaimDailyBonus.isEnabled = false
            binding.btnClaimDailyBonus.text = getString(R.string.interstitial_button_get_bonus_time_used, DailyBonusManager.BONUS_TIME_AWARD_SECONDS)
            binding.tvDailyBonusInfo.text = getString(R.string.bonus_time_used_info)
        }
    }

    private fun setupListeners() {
        binding.btnClaimDailyBonus.setOnClickListener {
            if (dailyBonusManager.awardBonusTime()) {
                // Time has been added to the bank. AppBlockerService will pick this up.
                Toast.makeText(this, getString(R.string.bonus_time_awarded_toast, DailyBonusManager.BONUS_TIME_AWARD_SECONDS), Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Grace time awarded for $blockedAppPackageName. Finishing InterstitialActivity.")
                finishAffinity() // Close this task, user returns to the blocked app, service re-evaluates
            } else {
                Toast.makeText(this, R.string.bonus_time_unavailable_toast, Toast.LENGTH_SHORT).show()
                updateBonusTimeButtonState()
            }
        }

        binding.btnEarnTime.setOnClickListener {
            Log.i(TAG, "Earn Time button clicked for $blockedAppPackageName. Launching PushupActivity.")
            val intent = Intent(this, PushupActivity::class.java).apply {
                putExtra(PushupActivity.EXTRA_BLOCKED_APP_NAME, blockedAppPackageName)
            }
            startActivity(intent)
            finishAffinity()
        }

        binding.btnTakeBreak.setOnClickListener {
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
            Toast.makeText(this, "DEV: Grace period award state reset!", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Developer action: Grace period award state reset.")
            updateBonusTimeButtonState()
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