package com.example.pushuppatrol

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
import com.example.pushuppatrol.databinding.ActivityAppSelectionBinding // Assuming ViewBinding

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private lateinit var appSelectionAdapter: AppSelectionAdapter
    private val installedAppsList = mutableListOf<AppInfo>()
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val TAG = "AppSelectionActivity"
        const val PREFS_NAME = "AppBlockerPrefs"
        const val KEY_LOCKED_APPS = "locked_app_packages"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
            // This lambda is called when a checkbox state changes in the adapter
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
        val previouslySelectedApps = loadLockedAppsList()
        val ownPackageName = packageName

        installedAppsList.clear()

        for (appInfoPm in packages) {
            // Exclude own app
            if (appInfoPm.packageName == ownPackageName) {
                Log.d(TAG, "Skipping own app: ${appInfoPm.packageName}")
                continue
            }

            if ((appInfoPm.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                pm.getLaunchIntentForPackage(appInfoPm.packageName) != null
            ) {
                try {
                    val appName = pm.getApplicationLabel(appInfoPm).toString()
                    val packageNameVal = appInfoPm.packageName // Renamed to avoid conflict
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
            .toSet() // Use a Set to store as string set in SharedPreferences

        sharedPreferences.edit().putStringSet(KEY_LOCKED_APPS, selectedPackages).apply()
        Log.d(TAG, "Saved locked apps: $selectedPackages")

        val intent = Intent("com.example.pushuppatrol.LOCKED_APPS_UPDATED")
        sendBroadcast(intent) // Or use LocalBroadcastManager for better security/efficiency
        Log.d(TAG, "Sent LOCKED_APPS_UPDATED broadcast.")
    }

    private fun loadLockedAppsList(): Set<String> {
        return sharedPreferences.getStringSet(KEY_LOCKED_APPS, emptySet()) ?: emptySet()
    }
}