package com.example.arcorestream.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataMuxer @Inject constructor() {

    // Packet types
    private val PACKET_TYPE_CAMERA = 1
    private val PACKET_TYPE_DEPTH = 2
    private val PACKET_TYPE_POSE = 3
    private val PACKET_TYPE_POINT_CLOUD = 4
    private val PACKET_TYPE_METADATA = 5

    // Connection info
    private var serverIP: String = ""
    private var serverPort: Int = 0

    // Socket for sending UDP data
    private var socket: DatagramSocket? = null

    // Processing state
    private val isRunning = AtomicBoolean(false)

    // Packet queue for sending
    private val packetQueue = ConcurrentLinkedQueue<ByteArray>()

    // Coroutine context
    private val muxerJob = SupervisorJob()
    private val muxerScope = CoroutineScope(Dispatchers.IO + muxerJob)
    private var senderJob: Job? = null

    /**
     * Start the muxer with target server info
     */
    fun start(serverIP: String, serverPort: Int) {
        if (isRunning.get()) {
            Timber.d("Muxer already running")
            return
        }

        this.serverIP = serverIP
        this.serverPort = serverPort

        try {
            // Create UDP socket
            socket = DatagramSocket()
            isRunning.set(true)

            // Start sender job
            senderJob = muxerScope.launch {
                packetSenderLoop()
            }

            Timber.d("Data muxer started for $serverIP:$serverPort")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start data muxer")
            socket?.close()
            socket = null
        }
    }

    /**
     * Stop the muxer
     */
    fun stop() {
        if (!isRunning.get()) {
            Timber.d("Muxer already stopped")
            return
        }

        isRunning.set(false)
        senderJob?.cancel()
        socket?.close()
        socket = null
        packetQueue.clear()

        Timber.d("Data muxer stopped")
    }

    /**
     * Send AR data to the server
     */
    fun sendARData(
        cameraData: ByteArray?,
        depthData: ByteArray?,
        poseData: ByteArray?,
        pointCloudData: ByteArray?,
        metadataJson: String
    ) {
        if (!isRunning.get() || socket == null) {
            return
        }

        try {
            // Queue camera data (if available)
            cameraData?.let {
                if (it.isNotEmpty()) {
                    val packet = createPacket(PACKET_TYPE_CAMERA, it)
                    packetQueue.add(packet)
                }
            }

            // Queue depth data (if available)
            depthData?.let {
                if (it.isNotEmpty()) {
                    val packet = createPacket(PACKET_TYPE_DEPTH, it)
                    packetQueue.add(packet)
                }
            }

            // Queue pose data (if available)
            poseData?.let {
                if (it.isNotEmpty()) {
                    val packet = createPacket(PACKET_TYPE_POSE, it)
                    packetQueue.add(packet)
                }
            }

            // Queue point cloud data (if available)
            pointCloudData?.let {
                if (it.isNotEmpty()) {
                    val packet = createPacket(PACKET_TYPE_POINT_CLOUD, it)
                    packetQueue.add(packet)
                }
            }

            // Queue metadata (always send this)
            val metadataPacket = createPacket(PACKET_TYPE_METADATA, metadataJson.toByteArray())
            packetQueue.add(metadataPacket)

        } catch (e: Exception) {
            Timber.e(e, "Error queueing AR data packets")
        }
    }

    /**
     * Create a packet with header for the specified data type
     */
    private fun createPacket(packetType: Int, data: ByteArray): ByteArray {
        val timestamp = System.currentTimeMillis()

        // Packet format:
        // - 4 bytes: packet type
        // - 8 bytes: timestamp
        // - 4 bytes: data length
        // - N bytes: data

        val buffer = ByteBuffer.allocate(16 + data.size)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(packetType)
        buffer.putLong(timestamp)
        buffer.putInt(data.size)
        buffer.put(data)

        return buffer.array()
    }

    /**
     * Background job to send packets from the queue
     */
    private suspend fun packetSenderLoop() {
        Timber.d("Packet sender loop started")

        while (isRunning.get() && socket != null) {
            try {
                // Poll a packet from the queue
                val packet = packetQueue.poll()

                if (packet != null) {
                    // Send packet to server
                    val address = InetAddress.getByName(serverIP)
                    val datagramPacket = DatagramPacket(packet, packet.size, address, serverPort)
                    socket?.send(datagramPacket)
                } else {
                    // No packet ready, short delay
                    kotlinx.coroutines.delay(5)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in packet sender loop")
                kotlinx.coroutines.delay(1000)  // Longer delay on error
            }
        }

        Timber.d("Packet sender loop ended")
    }

    /**
     * Clean up resources
     */
    fun release() {
        stop()
        muxerJob.cancel()
    }
}