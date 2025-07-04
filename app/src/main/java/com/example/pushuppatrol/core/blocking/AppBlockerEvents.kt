package com.example.pushuppatrol.core.blocking

interface TimeExpirationListener {
    fun onTimeExpired(expiredAppPackage: String?)
}