package com.example.arcorestream.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class NetworkManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Network state
    private val _connectionState = MutableStateFlow(ConnectionState.UNKNOWN)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Network type
    private val _networkType = MutableStateFlow(NetworkType.NONE)
    val networkType: StateFlow<NetworkType> = _networkType.asStateFlow()

    // Connection quality estimate
    private val _connectionQuality = MutableStateFlow(ConnectionQuality.UNKNOWN)
    val connectionQuality: StateFlow<ConnectionQuality> = _connectionQuality.asStateFlow()

    /**
     * Network callback to monitor connection changes
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            _connectionState.value = ConnectionState.CONNECTED
            updateNetworkInfo(network)
            Timber.d("Network available: $network")
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            _connectionState.value = ConnectionState.DISCONNECTED
            _networkType.value = NetworkType.NONE
            _connectionQuality.value = ConnectionQuality.UNKNOWN
            Timber.d("Network lost: $network")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, capabilities)
            updateNetworkInfo(network, capabilities)
            Timber.d("Network capabilities changed: $capabilities")
        }
    }

    init {
        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Initial connection check
        checkNetworkConnection()

        Timber.d("NetworkManager initialized")
    }

    /**
     * Check current network connection
     */
    private fun checkNetworkConnection() {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            _connectionState.value = ConnectionState.CONNECTED
            updateNetworkInfo(activeNetwork)
        } else {
            _connectionState.value = ConnectionState.DISCONNECTED
            _networkType.value = NetworkType.NONE
            _connectionQuality.value = ConnectionQuality.UNKNOWN
        }

        Timber.d("Network connection check: ${_connectionState.value}, ${_networkType.value}")
    }

    /**
     * Update network info based on current connection
     */
    private fun updateNetworkInfo(network: Network, capabilities: NetworkCapabilities? = null) {
        val netCapabilities = capabilities ?: connectivityManager.getNetworkCapabilities(network) ?: return

        // Determine network type
        val type = when {
            netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> NetworkType.BLUETOOTH
            else -> NetworkType.OTHER
        }
        _networkType.value = type

        // Estimate connection quality
        val quality = when {
            !netCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                ConnectionQuality.NO_INTERNET

            type == NetworkType.WIFI && netCapabilities.linkUpstreamBandwidthKbps > 20000 ->
                ConnectionQuality.EXCELLENT

            type == NetworkType.WIFI ||
                    (type == NetworkType.CELLULAR && netCapabilities.linkUpstreamBandwidthKbps > 2000) ->
                ConnectionQuality.GOOD

            netCapabilities.linkUpstreamBandwidthKbps > 500 ->
                ConnectionQuality.MODERATE

            else ->
                ConnectionQuality.POOR
        }
        _connectionQuality.value = quality
    }

    /**
     * Get current network upload bandwidth estimate in Kbps
     */
    fun getEstimatedUploadBandwidth(): Int {
        val activeNetwork = connectivityManager.activeNetwork ?: return 0
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return 0

        return capabilities.linkUpstreamBandwidthKbps
    }

    /**
     * Get current network download bandwidth estimate in Kbps
     */
    fun getEstimatedDownloadBandwidth(): Int {
        val activeNetwork = connectivityManager.activeNetwork ?: return 0
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return 0

        return capabilities.linkDownstreamBandwidthKbps
    }

    /**
     * Release resources
     */
    fun release() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Timber.d("NetworkManager released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing NetworkManager")
        }
    }
}