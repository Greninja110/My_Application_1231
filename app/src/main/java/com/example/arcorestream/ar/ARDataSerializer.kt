package com.example.arcorestream.ar

import android.graphics.ImageFormat
import android.media.Image
import com.google.ar.core.Pose
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.inject.Inject

class ARDataSerializer @Inject constructor() {

    // Compression settings
    private val useCompression = true
    private val compressionLevel = Deflater.BEST_SPEED  // Options: BEST_SPEED, DEFAULT_COMPRESSION, BEST_COMPRESSION

    /**
     * Serialize camera pose data to JSON
     */
    fun serializePose(pose: Pose?): String {
        if (pose == null) return "{}"

        val json = JSONObject()

        try {
            // Extract translation (position)
            val translation = pose.translation
            json.put("tx", translation[0])
            json.put("ty", translation[1])
            json.put("tz", translation[2])

            // Extract rotation (quaternion)
            val quaternion = pose.rotationQuaternion
            json.put("qx", quaternion[0])
            json.put("qy", quaternion[1])
            json.put("qz", quaternion[2])
            json.put("qw", quaternion[3])

            // Add timestamp
            json.put("timestamp", System.currentTimeMillis())
        } catch (e: Exception) {
            Timber.e(e, "Error serializing pose")
        }

        return json.toString()
    }

    /**
     * Serialize a collection of planes to JSON
     */
    fun serializePlanes(planes: Collection<com.google.ar.core.Plane>?): String {
        if (planes == null || planes.isEmpty()) return "[]"

        val jsonArray = JSONArray()

        try {
            for (plane in planes) {
                val jsonPlane = JSONObject()

                // Basic plane info
                jsonPlane.put("id", plane.hashCode())
                jsonPlane.put("type", plane.type.name)
                jsonPlane.put("trackingState", plane.trackingState.name)

                // Get center pose
                val pose = plane.centerPose
                val centerPos = pose.translation
                jsonPlane.put("centerX", centerPos[0])
                jsonPlane.put("centerY", centerPos[1])
                jsonPlane.put("centerZ", centerPos[2])

                // Get plane dimensions
                jsonPlane.put("extentX", plane.extentX)
                jsonPlane.put("extentZ", plane.extentZ)

                // Get polygon if available
                if (plane.polygon != null) {
                    val polygonArray = JSONArray()
                    val polygon = plane.polygon
                    for (i in 0 until polygon.limit() step 2) {
                        val vertex = JSONObject()
                        vertex.put("x", polygon[i])
                        vertex.put("z", polygon[i + 1])
                        polygonArray.put(vertex)
                    }
                    jsonPlane.put("polygon", polygonArray)
                }

                jsonArray.put(jsonPlane)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error serializing planes")
        }

        return jsonArray.toString()
    }

    /**
     * Serialize camera image data
     * Returns compressed byte array of the image data
     */
    fun serializeCameraImage(image: Image?): ByteArray? {
        if (image == null) return null

        try {
            // For YUV_420_888 format (common for camera preview)
            if (image.format == ImageFormat.YUV_420_888) {
                val width = image.width
                val height = image.height

                // Get Y plane (luminance)
                val yPlane = image.planes[0]
                val yBuffer = yPlane.buffer
                val ySize = yBuffer.remaining()

                // Get U and V planes (chrominance)
                val uPlane = image.planes[1]
                val uBuffer = uPlane.buffer
                val uSize = uBuffer.remaining()

                val vPlane = image.planes[2]
                val vBuffer = vPlane.buffer
                val vSize = vBuffer.remaining()

                // Create output buffer
                val data = ByteArray(ySize + uSize + vSize + 16)  // Extra bytes for metadata
                val buffer = ByteBuffer.wrap(data)

                // Add metadata (for reconstruction)
                buffer.putInt(width)
                buffer.putInt(height)
                buffer.putInt(ySize)
                buffer.putInt(uSize)

                // Copy Y plane
                buffer.put(yBuffer)

                // Copy U plane
                buffer.put(uBuffer)

                // Copy V plane
                buffer.put(vBuffer)

                // Compress if needed
                return if (useCompression) {
                    compressData(data)
                } else {
                    data
                }
            } else {
                Timber.e("Unsupported image format: ${image.format}")
                return null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error serializing camera image")
            return null
        }
    }

    /**
     * Compress data using Deflate
     */
    private fun compressData(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream(data.size / 2)
        val deflater = Deflater(compressionLevel)
        val deflaterOutputStream = DeflaterOutputStream(outputStream, deflater)

        try {
            deflaterOutputStream.write(data)
            deflaterOutputStream.finish()
            deflaterOutputStream.close()

            val compressedData = outputStream.toByteArray()
            Timber.d("Compressed data from ${data.size} to ${compressedData.size} bytes")

            return compressedData
        } catch (e: Exception) {
            Timber.e(e, "Error compressing data")
            return data  // Return original data on error
        }
    }

    /**
     * Creates a metadata packet containing all AR information
     */
    fun createMetadataPacket(
        cameraPose: Pose?,
        depthMetadata: Map<String, Any>,
        trackingState: com.google.ar.core.TrackingState?,
        planesCount: Int
    ): String {
        val json = JSONObject()

        try {
            // Basic device info
            json.put("device", "nothing_phone_2")
            json.put("timestamp", System.currentTimeMillis())

            // AR tracking info
            json.put("tracking_state", trackingState?.name ?: "UNKNOWN")
            json.put("planes_detected", planesCount)

            // Camera info
            if (cameraPose != null) {
                val cameraJson = JSONObject()
                val translation = cameraPose.translation
                val quaternion = cameraPose.rotationQuaternion

                cameraJson.put("position", JSONArray().apply {
                    put(translation[0])
                    put(translation[1])
                    put(translation[2])
                })

                cameraJson.put("rotation", JSONArray().apply {
                    put(quaternion[0])
                    put(quaternion[1])
                    put(quaternion[2])
                    put(quaternion[3])
                })

                json.put("camera", cameraJson)
            }

            // Depth info
            val depthJson = JSONObject()
            for ((key, value) in depthMetadata) {
                depthJson.put(key, value)
            }
            json.put("depth", depthJson)

        } catch (e: Exception) {
            Timber.e(e, "Error creating metadata packet")
        }

        return json.toString()
    }
}