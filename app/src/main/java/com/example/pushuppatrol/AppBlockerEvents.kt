package com.example.pushuppatrol

interface TimeExpirationListener {
    fun onTimeExpired(expiredAppPackage: String?)
}