package com.example.arcorestream.ar

import android.media.Image
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.min

class DepthDataProcessor @Inject constructor() {

    // Full depth map
    private var depthWidth: Int = 0
    private var depthHeight: Int = 0
    private var depthData: ByteBuffer? = null

    // Confidence map (if available)
    private var confidenceData: ByteBuffer? = null

    // For downsampled depth
    private var downsampledDepthData: ByteBuffer? = null
    private var downsampledWidth: Int = 0
    private var downsampledHeight: Int = 0
    private val downsampleFactor: Int = 4  // Reduce size by factor of 4

    /**
     * Process depth data from ARCore depth image
     * @return true if depth was processed successfully
     */
    fun processDepthImage(depthImage: Image?): Boolean {
        if (depthImage == null) {
            Timber.d("No depth image available")
            return false
        }

        try {
            depthWidth = depthImage.width
            depthHeight = depthImage.height

            // Get depth data from image planes
            val planes = depthImage.planes

            if (planes.size < 1) {
                Timber.e("Depth image has no planes")
                return false
            }

            // Get depth data from first plane
            val depthPlane = planes[0]
            val depthBuffer = depthPlane.buffer

            // Create a copy of the buffer to avoid issues with image closing
            depthData = ByteBuffer.allocateDirect(depthBuffer.capacity())
                .order(ByteOrder.nativeOrder())

            // Copy data
            depthBuffer.rewind()
            depthData?.put(depthBuffer)
            depthData?.rewind()

            // If confidence data is available (usually in the second plane)
            if (planes.size > 1) {
                val confidencePlane = planes[1]
                val confidenceBuffer = confidencePlane.buffer

                confidenceData = ByteBuffer.allocateDirect(confidenceBuffer.capacity())
                    .order(ByteOrder.nativeOrder())

                confidenceBuffer.rewind()
                confidenceData?.put(confidenceBuffer)
                confidenceData?.rewind()
            }

            // Create downsampled version for more efficient transmission
            createDownsampledDepth()

            Timber.d("Processed depth image: ${depthWidth}x${depthHeight}, downsampled to ${downsampledWidth}x${downsampledHeight}")
            return true
        } catch (e: Exception) {
            Timber.e(e, "Error processing depth image")
            return false
        }
    }

    /**
     * Creates a downsampled version of the depth data for more efficient transmission
     */
    private fun createDownsampledDepth() {
        if (depthData == null || depthWidth <= 0 || depthHeight <= 0) {
            return
        }

        // Calculate downsampled dimensions
        downsampledWidth = depthWidth / downsampleFactor
        downsampledHeight = depthHeight / downsampleFactor

        // Create or reuse buffer
        if (downsampledDepthData == null ||
            downsampledDepthData?.capacity() != downsampledWidth * downsampledHeight * 2) {
            // 2 bytes per pixel for depth (16-bit)
            downsampledDepthData = ByteBuffer.allocateDirect(downsampledWidth * downsampledHeight * 2)
                .order(ByteOrder.nativeOrder())
        } else {
            downsampledDepthData?.clear()
        }

        // Downsample by averaging blocks of pixels
        val depthShorts = depthData?.asShortBuffer()
        val downsampledShorts = downsampledDepthData?.asShortBuffer()

        for (y in 0 until downsampledHeight) {
            for (x in 0 until downsampledWidth) {
                // Calculate average depth in this block
                var sum: Long = 0
                var count = 0

                for (blockY in 0 until downsampleFactor) {
                    for (blockX in 0 until downsampleFactor) {
                        val srcX = x * downsampleFactor + blockX
                        val srcY = y * downsampleFactor + blockY

                        if (srcX < depthWidth && srcY < depthHeight) {
                            val idx = srcY * depthWidth + srcX
                            val depth = depthShorts?.get(idx) ?: 0
                            if (depth > 0) {  // Skip invalid depths
                                sum += depth.toLong()
                                count++
                            }
                        }
                    }
                }

                // Store the average depth
                val avgDepth = if (count > 0) (sum / count).toShort() else 0
                downsampledShorts?.put(avgDepth)
            }
        }
    }

    /**
     * Gets raw depth data for transmission
     */
    fun getDepthData(): ByteArray? {
        // Use downsampled data for efficiency
        val buffer = downsampledDepthData ?: return null

        buffer.rewind()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }

    /**
     * Gets metadata about the depth (width, height, etc.)
     */
    fun getDepthMetadata(): Map<String, Any> {
        return mapOf(
            "original_width" to depthWidth,
            "original_height" to depthHeight,
            "width" to downsampledWidth,
            "height" to downsampledHeight,
            "downsample_factor" to downsampleFactor,
            "format" to "depth16", // 16-bit depth values
            "timestamp" to System.currentTimeMillis()
        )
    }

    /**
     * Get a sparse point cloud from the depth data (for more efficient transmission)
     * Returns an array of [x, y, z, confidence] values
     */
    fun getSparsePointCloud(frame: Frame?, maxPoints: Int = 1000): ByteArray? {
        if (frame == null) return null

        try {
            // Try to get depth points
            val pointCloud = frame.acquirePointCloud()
            if (pointCloud.points.remaining() == 0) {
                Timber.d("No depth points available")
                pointCloud.release()
                return null
            }

            // Limit the number of points for efficiency
            val pointCount = min(pointCloud.points.remaining() / 4, maxPoints)

            // Create buffer for points: xyzc format (where c is confidence)
            val buffer = ByteBuffer.allocateDirect(pointCount * 4 * 4)  // 4 floats per point, 4 bytes per float
                .order(ByteOrder.nativeOrder())

            // Copy points
            val points = pointCloud.points
            val pointsData = FloatArray(4)

            for (i in 0 until pointCount) {
                points.get(pointsData)
                buffer.putFloat(pointsData[0])  // x
                buffer.putFloat(pointsData[1])  // y
                buffer.putFloat(pointsData[2])  // z
                buffer.putFloat(pointsData[3])  // confidence
            }

            // Convert to byte array
            buffer.rewind()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            pointCloud.release()

            Timber.d("Generated sparse point cloud with $pointCount points")
            return data
        } catch (e: Exception) {
            Timber.e(e, "Error generating sparse point cloud")
            return null
        }
    }

    /**
     * Get dense point cloud from current depth data
     * This is more detailed but also larger for transmission
     */
    fun getDensePointCloud(maxPoints: Int = 10000): ByteArray? {
        if (depthData == null) return null

        try {
            // Create buffer for points
            val buffer = ByteBuffer.allocateDirect(maxPoints * 3 * 4)  // 3 floats per point, 4 bytes per float
                .order(ByteOrder.nativeOrder())

            // Sample points from depth map
            val depthShorts = depthData?.asShortBuffer()
            val stepX = depthWidth / sqrt(maxPoints.toDouble(), depthWidth.toDouble() / depthHeight.toDouble())
            val stepY = depthHeight / sqrt(maxPoints.toDouble(), depthHeight.toDouble() / depthWidth.toDouble())

            var pointCount = 0
            var y = 0
            while (y < depthHeight && pointCount < maxPoints) {
                var x = 0
                while (x < depthWidth && pointCount < maxPoints) {
                    val idx = y * depthWidth + x
                    val depth = depthShorts?.get(idx) ?: 0

                    if (depth > 0) {  // Valid depth
                        // Convert pixel coordinates to 3D coordinates (simplified)
                        // In a real implementation, you'd use proper camera intrinsics
                        val normalizedX = (x / depthWidth.toFloat()) * 2 - 1
                        val normalizedY = (y / depthHeight.toFloat()) * 2 - 1
                        val z = depth / 1000.0f  // Convert to meters

                        buffer.putFloat(normalizedX * z)  // x
                        buffer.putFloat(normalizedY * z)  // y
                        buffer.putFloat(z)  // z

                        pointCount++
                    }

                    x += stepX.toInt().coerceAtLeast(1)
                }

                y += stepY.toInt().coerceAtLeast(1)
            }

            // Trim buffer to actual size
            val actualSize = pointCount * 3 * 4
            val trimmedBuffer = ByteBuffer.allocateDirect(actualSize)
                .order(ByteOrder.nativeOrder())

            buffer.rewind()
            for (i in 0 until actualSize) {
                trimmedBuffer.put(buffer.get())
            }

            // Convert to byte array
            trimmedBuffer.rewind()
            val data = ByteArray(trimmedBuffer.remaining())
            trimmedBuffer.get(data)

            Timber.d("Generated dense point cloud with $pointCount points")
            return data
        } catch (e: Exception) {
            Timber.e(e, "Error generating dense point cloud")
            return null
        }
    }

    private fun sqrt(value: Double, aspectRatio: Double): Double {
        return Math.sqrt(value / aspectRatio) * Math.sqrt(aspectRatio)
    }

    /**
     * Cleanup resources
     */
    fun release() {
        depthData = null
        confidenceData = null
        downsampledDepthData = null
    }
}