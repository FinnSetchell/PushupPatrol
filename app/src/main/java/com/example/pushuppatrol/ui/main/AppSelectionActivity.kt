package com.example.pushuppatrol.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pushuppatrol.core.blocking.AppBlockerService
import com.example.pushuppatrol.core.blocking.AppInfo
import com.example.pushuppatrol.databinding.ActivityAppSelectionBinding

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private lateinit var appSelectionAdapter: AppSelectionAdapter
    private val installedAppsList = mutableListOf<AppInfo>()
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val TAG = "AppSelectionActivity"
        // PREFS_NAME and KEY_LOCKED_APPS can be accessed via AppBlockerService.PREFS_NAME
        // if you want a single source of truth, but keeping them here is also fine
        // as long as they match.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Use PREFS_NAME from AppBlockerService to ensure consistency
        sharedPreferences = getSharedPreferences(AppBlockerService.PREFS_NAME, Context.MODE_PRIVATE)

        binding.btnSaveSelections.setOnClickListener {
            saveLockedAppsList()
            Toast.makeText(this, "Selections Saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        setupRecyclerView()
        loadInstalledApps()
    }

    private fun setupRecyclerView() {
        appSelectionAdapter = AppSelectionAdapter(installedAppsList) { appInfo ->
            Log.d(TAG, "App selected: ${appInfo.appName}, New state: ${appInfo.isSelected}")
        }
        binding.rvAppList.apply {
            layoutManager = LinearLayoutManager(this@AppSelectionActivity)
            adapter = appSelectionAdapter
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        // Use KEY_LOCKED_APPS from AppBlockerService
        val previouslySelectedApps = sharedPreferences.getStringSet(AppBlockerService.KEY_LOCKED_APPS, emptySet()) ?: emptySet()
        val ownPackageName = packageName

        installedAppsList.clear()

        for (appInfoPm in packages) {
            if (appInfoPm.packageName == ownPackageName) {
                Log.d(TAG, "Skipping own app: ${appInfoPm.packageName}")
                continue
            }

            if ((appInfoPm.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                pm.getLaunchIntentForPackage(appInfoPm.packageName) != null
            ) {
                try {
                    val appName = pm.getApplicationLabel(appInfoPm).toString()
                    val packageNameVal = appInfoPm.packageName
                    val icon = pm.getApplicationIcon(appInfoPm)
                    val isSelected = previouslySelectedApps.contains(packageNameVal)

                    installedAppsList.add(AppInfo(appName, packageNameVal, icon, isSelected))
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Error loading app info for ${appInfoPm.packageName}", e)
                }
            }
        }

        installedAppsList.sortBy { it.appName.lowercase() }
        appSelectionAdapter.notifyDataSetChanged()
        Log.d(TAG, "Loaded ${installedAppsList.size} non-system, launchable apps (excluding own).")
    }

    private fun saveLockedAppsList() {
        val selectedPackages = installedAppsList
            .filter { it.isSelected }
            .map { it.packageName }
            .toSet()

        // Use KEY_LOCKED_APPS from AppBlockerService
        // Using commit() for synchronous write as discussed
        val success = sharedPreferences.edit().putStringSet(AppBlockerService.KEY_LOCKED_APPS, selectedPackages).commit()
        Log.d(TAG, "Saved locked apps to SharedPreferences (success: $success): $selectedPackages")

        // --- MODIFIED: Replace broadcast with startService ---
        val updateServiceIntent = Intent(this, AppBlockerService::class.java).apply {
            action = AppBlockerService.ACTION_REFRESH_LOCKED_APPS // <<< USE NEW ACTION
        }
        try {
            // For a service that might not be running or is an accessibility service,
            // using startService is appropriate here.
            startService(updateServiceIntent)
            Log.i(TAG, "Sent ACTION_REFRESH_LOCKED_APPS to AppBlockerService via startService.")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ACTION_REFRESH_LOCKED_APPS to AppBlockerService", e)
            // Consider informing the user or other fallback if this fails, though unlikely for an internal service.
        }
    }

    // This local loadLockedAppsList is only used for populating the UI initially in this activity.
    // It's fine to keep it, or you could also remove it if you always rely on the
    // adapter's state after loadInstalledApps.
    // private fun loadLockedAppsList(): Set<String> {
    //     return sharedPreferences.getStringSet(AppBlockerService.KEY_LOCKED_APPS, emptySet()) ?: emptySet()
    // }
}