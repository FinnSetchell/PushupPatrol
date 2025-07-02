package com.example.pushuppatrol

import android.util.Log

object AppBlockerEventManager {
    private const val TAG = "AppBlockerEventManager"
    private var listener: TimeExpirationListener? = null

    fun setTimeExpirationListener(listener: TimeExpirationListener?) {
        this.listener = listener
        Log.d(TAG, "TimeExpirationListener ${if (listener != null) "set" else "cleared"}")
    }

    fun reportTimeExpired(expiredAppPackage: String?) {
        Log.d(TAG, "reportTimeExpired called for $expiredAppPackage. Listener: $listener")
        listener?.onTimeExpired(expiredAppPackage)
    }
}