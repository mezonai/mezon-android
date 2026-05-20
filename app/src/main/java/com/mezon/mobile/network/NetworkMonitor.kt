package com.mezon.mobile.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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

    private val _isOnline = MutableStateFlow(readHasInternetCapability())
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
        val nextOnline = readHasInternetCapability()
        if (force || _isOnline.value != nextOnline) {
            Log.d(TAG, "isOnline=$nextOnline")
            _isOnline.value = nextOnline
        }
    }

    private fun readHasInternetCapability(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
