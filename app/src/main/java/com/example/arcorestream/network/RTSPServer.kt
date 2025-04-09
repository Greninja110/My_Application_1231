package com.example.arcorestream.network

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import timber.log.Timber
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class RTSPServer @Inject constructor(
    private val context: Context
) {
    private val isStarted = AtomicBoolean(false)

    // Network info
    private var localIpAddress: String? = null
    private var rtspPort: Int = 8086  // Default RTSP port

    // Streaming configuration
    private val videoWidth = 1280
    private val videoHeight = 720
    private val videoBitrate = 4000000  // 4 Mbps
    private val videoFramerate = 30

    /**
     * Initializes the RTSP server
     */
    fun init() {
        try {
            // Get local IP address
            localIpAddress = getLocalIpAddress()

            // Create and configure media format
            createVideoMediaFormat()

            Timber.d("RTSP server initialized with IP $localIpAddress and port $rtspPort")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize RTSP server")
        }
    }

    /**
     * Configure video format for streaming
     */
    private fun createVideoMediaFormat() {
        val videoMediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
        videoMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
        videoMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramerate)
        videoMediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, 0x7F000789) // Surface format constant
        videoMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)  // Keyframe every 2 seconds

        // For better streaming performance
        videoMediaFormat.setInteger(MediaFormat.KEY_BITRATE_MODE, 1) // VBR mode constant
        videoMediaFormat.setInteger(MediaFormat.KEY_COMPLEXITY, 1) // Low complexity

        Timber.d("Video format configured for streaming")
    }

    /**
     * Start the RTSP server
     */
    fun start(): Boolean {
        if (isStarted.get()) {
            Timber.d("RTSP server already running")
            return true
        }

        try {
            // Make sure we have a valid local IP
            if (localIpAddress == null) {
                localIpAddress = getLocalIpAddress()
            }

            // Start your server here (simplified for now)
            Timber.d("RTSP server started at rtsp://$localIpAddress:$rtspPort/")
            isStarted.set(true)
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start RTSP server")
            return false
        }
    }

    /**
     * Stop the RTSP server
     */
    fun stop() {
        if (!isStarted.get()) {
            Timber.d("RTSP server already stopped")
            return
        }

        try {
            // Stop your server here
            isStarted.set(false)
            Timber.d("RTSP server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop RTSP server")
        }
    }

    /**
     * Get the URL for the RTSP stream
     */
    fun getStreamUrl(): String? {
        if (!isStarted.get() || localIpAddress == null) {
            return null
        }

        return "rtsp://$localIpAddress:$rtspPort/"
    }

    /**
     * Get network info such as IP and connection status
     */
    fun getNetworkInfo(): Map<String, Any> {
        val info = mutableMapOf<String, Any>()

        info["ip_address"] = localIpAddress ?: "unknown"
        info["port"] = rtspPort
        info["is_running"] = isStarted.get()
        info["stream_url"] = getStreamUrl() ?: "not_available"

        return info
    }

    /**
     * Get local IP address of the device (IPv4)
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Find IPv4 address that is not a loopback
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && hostAddress.contains(".")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting network address")
        }

        return null
    }

    /**
     * Clean up resources
     */
    fun release() {
        try {
            stop()
            Timber.d("RTSP server resources released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing RTSP server resources")
        }
    }
}