package com.bombest.music.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.car.app.connection.CarConnection

/**
 * Phase 3 polish: Android Auto / automotive UI detection and network gating for prefetch.
 */
object CarUiPolish {

    /** True when phone is projecting to a car or running on embedded automotive UI. */
    fun isCarUiActive(connectionType: Int?): Boolean {
        if (connectionType == null) return false
        return connectionType == CarConnection.CONNECTION_TYPE_PROJECTION ||
            connectionType == CarConnection.CONNECTION_TYPE_NATIVE
    }

    /** Unmetered Wi‑Fi / Ethernet (matches WorkManager UNMETERED constraint intent). */
    fun isUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    }
}
