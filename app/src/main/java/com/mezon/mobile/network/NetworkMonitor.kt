package com.mezon.mobile.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(readValidatedInternet())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publishFromActiveNetwork()
        }

        override fun onLost(network: Network) {
            publishFromActiveNetwork()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            publishFromActiveNetwork()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            publishFromActiveNetwork()
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(connectivityCallback)
        publishFromActiveNetwork(force = true)
    }

    private fun publishFromActiveNetwork(force: Boolean = false) {
        val nextOnline = readValidatedInternet()
        if (force || _isOnline.value != nextOnline) {
            Log.d(TAG, "isOnline=$nextOnline")
            _isOnline.value = nextOnline
        }
    }

    private fun readValidatedInternet(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasValidatedInternet()
    }

    private fun NetworkCapabilities.hasValidatedInternet(): Boolean {
        if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        ) {
            return false
        }
        return true
    }
}
