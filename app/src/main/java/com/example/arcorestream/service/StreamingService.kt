package com.example.arcorestream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.arcorestream.MainActivity
import com.example.arcorestream.R
import com.example.arcorestream.ar.ARCoreSession
import com.example.arcorestream.ar.ARDataSerializer
import com.example.arcorestream.ar.DepthDataProcessor
import com.example.arcorestream.network.NetworkManager
import com.example.arcorestream.network.RTSPServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class StreamingService : Service() {

    @Inject lateinit var rtspServer: RTSPServer
    @Inject lateinit var networkManager: NetworkManager
    @Inject lateinit var arCoreSession: ARCoreSession
    @Inject lateinit var depthProcessor: DepthDataProcessor
    @Inject lateinit var dataSerializer: ARDataSerializer
    @Inject lateinit var dataMuxer: DataMuxer

    // Binder for clients to access this service
    private val binder = LocalBinder()

    // Service state
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _networkInfo = MutableStateFlow<Map<String, Any>>(emptyMap())
    val networkInfo: StateFlow<Map<String, Any>> = _networkInfo.asStateFlow()

    // Coroutine scope for service operations
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    // Wake lock to keep CPU active
    private var wakeLock: PowerManager.WakeLock? = null

    // Server connection info
    private var serverIP = ""
    private var serverPort = 0

    // Processing flags
    private val isRunning = AtomicBoolean(false)

    // Notification
    private val NOTIFICATION_ID = 12345
    private val CHANNEL_ID = "ARCoreStream_Channel"

    /**
     * Binder class for service access
     */
    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize RTSP server
        rtspServer.init()

        // Create notification channel
        createNotificationChannel()

        Timber.d("Streaming service created")
    }

    override fun onBind(intent: Intent?): IBinder {
        Timber.d("Service bound")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("Service start command received")
        return START_STICKY
    }

    /**
     * Create notification channel for foreground service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AR Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for AR streaming to laptop"
                enableLights(true)
                lightColor = Color.BLUE
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)

            Timber.d("Notification channel created")
        }
    }

    /**
     * Create foreground notification
     */
    private fun createForegroundNotification(): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AR Streaming")
            .setContentText("Streaming AR data to ${serverIP}:${serverPort}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Start streaming to server
     */
    fun startStreaming(serverIP: String, serverPort: Int) {
        if (isRunning.get()) {
            Timber.d("Already streaming, ignoring start request")
            return
        }

        this.serverIP = serverIP
        this.serverPort = serverPort

        // Start service in foreground
        startForeground(NOTIFICATION_ID, createForegroundNotification())

        // Acquire wake lock to prevent CPU from sleeping
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARCoreStream::StreamingLock").apply {
                acquire(3600000L /*1 hour*/)
            }
        }

        // Start RTSP server
        val rtspStarted = rtspServer.start()
        if (!rtspStarted) {
            Timber.e("Failed to start RTSP server")
            stopStreaming()
            return
        }

        // Start data muxer
        dataMuxer.start(serverIP, serverPort)

        // Mark as running
        isRunning.set(true)
        _isStreaming.value = true

        // Update network info
        updateNetworkInfo()

        // Start processing in background
        serviceScope.launch {
            startARDataProcessing()
        }

        Timber.d("Streaming started to $serverIP:$serverPort")
    }

    /**
     * Stop streaming
     */
    fun stopStreaming() {
        if (!isRunning.get()) {
            Timber.d("Not streaming, ignoring stop request")
            return
        }

        // Stop RTSP server
        rtspServer.stop()

        // Stop data muxer
        dataMuxer.stop()

        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }

        // Update state
        isRunning.set(false)
        _isStreaming.value = false

        // Stop foreground
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Update network info
        updateNetworkInfo()

        Timber.d("Streaming stopped")
    }

    /**
     * Process AR data and send to server
     */
    private suspend fun startARDataProcessing() {
        Timber.d("Starting AR data processing")

        while (isRunning.get()) {
            try {
                // Get latest AR frame
                val frame = arCoreSession.update()

                if (frame != null) {
                    // Process depth data
                    val depthImage = arCoreSession.getDepthImage()
                    if (depthImage != null) {
                        depthProcessor.processDepthImage(depthImage)
                    }

                    // Get camera image
                    val cameraImage = arCoreSession.acquireCameraImage()

                    // Get camera pose
                    val cameraPose = arCoreSession.getCameraPose()

                    // Get planes
                    val planes = arCoreSession.getPlanes()

                    // Prepare data packets
                    val poseData = dataSerializer.serializePose(cameraPose)
                    val depthData = depthProcessor.getDepthData()
                    val depthMetadata = depthProcessor.getDepthMetadata()
                    val pointCloud = depthProcessor.getSparsePointCloud(frame)
                    val cameraData = dataSerializer.serializeCameraImage(cameraImage)

                    // Create metadata packet
                    val metadataPacket = dataSerializer.createMetadataPacket(
                        cameraPose = cameraPose,
                        depthMetadata = depthMetadata,
                        trackingState = arCoreSession.getTrackingState(),
                        planesCount = planes?.size ?: 0
                    )

                    // Send to data muxer
                    dataMuxer.sendARData(
                        cameraData = cameraData,
                        depthData = depthData,
                        poseData = poseData.toByteArray(),
                        pointCloudData = pointCloud,
                        metadataJson = metadataPacket
                    )

                    // Clean up
                    cameraImage?.close()
                }

                // Update network info periodically
                updateNetworkInfo()

                // Small delay to prevent CPU overload
                kotlinx.coroutines.delay(16)  // ~60fps

            } catch (e: Exception) {
                Timber.e(e, "Error in AR data processing")
                kotlinx.coroutines.delay(1000)  // Wait before retry
            }
        }

        Timber.d("AR data processing stopped")
    }

    /**
     * Update network info for UI
     */
    private fun updateNetworkInfo() {
        val info = rtspServer.getNetworkInfo().toMutableMap()

        // Add connection quality
        info["connection_quality"] = networkManager.connectionQuality.value.name
        info["network_type"] = networkManager.networkType.value.name
        info["upload_bandwidth_kbps"] = networkManager.getEstimatedUploadBandwidth()

        // Add server connection info
        info["server_ip"] = serverIP
        info["server_port"] = serverPort

        _networkInfo.value = info
    }

    override fun onDestroy() {
        Timber.d("Service being destroyed")

        // Stop streaming if active
        if (isRunning.get()) {
            stopStreaming()
        }

        // Release resources
        rtspServer.release()

        // Cancel coroutines
        serviceScope.cancel()

        super.onDestroy()
    }
}