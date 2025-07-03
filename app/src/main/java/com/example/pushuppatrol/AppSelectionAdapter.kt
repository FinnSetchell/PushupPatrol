package com.example.pushuppatrol

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppSelectionAdapter(
    private val appList: MutableList<AppInfo>,
    private val onAppSelected: (AppInfo) -> Unit // Callback for selection change
) : RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder>() {

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val appName: TextView = itemView.findViewById(R.id.tvAppName)
        val appCheckbox: CheckBox = itemView.findViewById(R.id.cbAppSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = appList[position]

        holder.appIcon.setImageDrawable(appInfo.icon)
        holder.appName.text = appInfo.appName

        // --- CRITICAL SECTION FOR PREVENTING GHOST SELECTIONS ---
        // 1. Remove any existing listener before programmatically changing checkbox state.
        //    This prevents the listener from firing due to you setting the state.
        holder.appCheckbox.setOnCheckedChangeListener(null)

        // 2. Set the checkbox state based *only* on your data model.
        holder.appCheckbox.isChecked = appInfo.isSelected

        // 3. Re-attach the listener for user interactions.
        holder.appCheckbox.setOnCheckedChangeListener { _, isChecked ->
            // Use holder.getAdapterPosition() for safety.
            // It's the same as bindingAdapterPosition if not using ConcatAdapter.
            val currentPosition = holder.adapterPosition // Renamed from getAdapterPosition for Kotlin property access
            if (currentPosition != RecyclerView.NO_POSITION) {
                // Get the correct AppInfo object for this position
                val currentAppInfo = appList[currentPosition]
                // Only update and notify if the state actually changed
                if (currentAppInfo.isSelected != isChecked) {
                    currentAppInfo.isSelected = isChecked
                    onAppSelected(currentAppInfo) // Notify the Activity/ViewModel
                }
            }
        }
        // --- END CRITICAL SECTION ---

        // Optional: Handle item clicks to toggle checkbox
        // This makes the whole row clickable.
        holder.itemView.setOnClickListener {
            // Use holder.getAdapterPosition() for safety.
            val currentPosition = holder.adapterPosition // Renamed
            if (currentPosition != RecyclerView.NO_POSITION) {
                // Programmatically toggle the checkbox.
                // The CheckBox's setOnCheckedChangeListener (set above) will then handle
                // updating the model (appInfo.isSelected) and calling the onAppSelected callback.
                holder.appCheckbox.isChecked = !holder.appCheckbox.isChecked
            }
        }
    }

    override fun getItemCount(): Int = appList.size

    fun updateApps(newApps: List<AppInfo>) {
        appList.clear()
        appList.addAll(newApps)
        notifyDataSetChanged() // Consider using DiffUtil for better performance
    }
}