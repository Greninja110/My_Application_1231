package com.example.arcorestream.ar

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.Image
import android.view.Surface
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import timber.log.Timber
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ARCoreSession @Inject constructor() : Closeable {

    private var session: Session? = null
    private val isSessionPaused = AtomicBoolean(true)
    private var config: Config? = null
    private var cameraTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    // Latest frame data
    private var latestFrame: Frame? = null
    private var depthImage: Image? = null

    // Error listener
    private var errorCallback: ((Exception) -> Unit)? = null

    /**
     * Initializes the AR session with the activity context
     */
    fun initialize(activity: Activity) {
        try {
            // Check AR availability
            if (ArCoreApk.getInstance().checkAvailability(activity) != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
                Timber.e("ARCore not installed or not supported")
                errorCallback?.invoke(Exception("ARCore not installed or not supported"))
                return
            }

            if (session == null) {
                session = Session(activity)
                Timber.d("ARCore session created")
            }

            configureSession()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize ARCore session")
            errorCallback?.invoke(e)
        }
    }

    /**
     * Configure ARCore session with depth
     */
    private fun configureSession() {
        session?.let { arSession ->
            config = Config(arSession)

            // Enable depth API
            config?.depthMode = Config.DepthMode.AUTOMATIC

            // Enable plane detection
            config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

            // Enable light estimation for better AR experience
            config?.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

            // Set focus mode
            config?.focusMode = Config.FocusMode.AUTO

            // Update config
            arSession.configure(config)

            Timber.d("ARCore session configured with depth mode: ${config?.depthMode}")
        }
    }

    /**
     * Sets the surface texture for camera preview
     */
    fun setCameraTexture(texture: SurfaceTexture) {
        this.cameraTexture = texture
        if (cameraSurface != null) {
            cameraSurface?.release()
        }
        cameraSurface = Surface(texture)

        // Get the texture name/ID from the texture parameter
        // Instead of calling detachFromGLContext directly
        session?.let { session ->
            // Get the texture ID (should be set when texture is created)
            val textureId = texture.hashCode() // or another way to get a unique ID
            session.setCameraTextureName(textureId)
        }
    }

    /**
     * Resumes the AR session
     */
    fun resume() {
        try {
            session?.let { arSession ->
                if (isSessionPaused.getAndSet(false)) {
                    Timber.d("Resuming AR session")
                    arSession.resume()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resume ARCore session")
            errorCallback?.invoke(e)
        }
    }

    /**
     * Pauses the AR session
     */
    fun pause() {
        try {
            session?.let { arSession ->
                if (!isSessionPaused.getAndSet(true)) {
                    Timber.d("Pausing AR session")
                    arSession.pause()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to pause ARCore session")
        }
    }

    /**
     * Updates and returns the current frame
     */
    fun update(): Frame? {
        if (isSessionPaused.get()) {
            return null
        }

        try {
            session?.let { arSession ->
                latestFrame = arSession.update()

                // Try to acquire depth image if available
                latestFrame?.let { frame ->
                    try {
                        // Release previous depth image if it exists
                        depthImage?.close()

                        // Try to acquire a new depth image
                        depthImage = frame.acquireDepthImage16Bits()
                    } catch (e: NotYetAvailableException) {
                        // Depth might not be available yet, this is normal
                    } catch (e: Exception) {
                        Timber.e(e, "Error acquiring depth image")
                    }
                }

                return latestFrame
            }
        } catch (e: Exception) {
            Timber.e(e, "Error during AR frame update")
            errorCallback?.invoke(e)
        }

        return null
    }

    /**
     * Returns the current camera image (for streaming)
     */
    fun acquireCameraImage(): Image? {
        return try {
            latestFrame?.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            // Camera image might not be available yet, this is normal
            null
        } catch (e: Exception) {
            Timber.e(e, "Error acquiring camera image")
            null
        }
    }

    /**
     * Returns the current depth image
     */
    fun getDepthImage(): Image? = depthImage

    /**
     * Gets the camera pose (position and orientation)
     */
    fun getCameraPose(): Pose? = latestFrame?.camera?.pose

    /**
     * Gets the tracked planes from the current frame
     */
    fun getPlanes(): Collection<Plane>? = latestFrame?.getUpdatedTrackables(Plane::class.java)

    /**
     * Gets the tracking state of the camera
     */
    fun getTrackingState(): TrackingState? = latestFrame?.camera?.trackingState

    /**
     * Set an error callback to be notified of ARCore errors
     */
    fun setErrorCallback(callback: (Exception) -> Unit) {
        this.errorCallback = callback
    }

    /**
     * Checks if depth is available
     */
    fun isDepthAvailable(): Boolean = depthImage != null

    override fun close() {
        Timber.d("Closing ARCore session")

        try {
            depthImage?.close()
            cameraSurface?.release()
            session?.close()

            depthImage = null
            cameraSurface = null
            cameraTexture = null
            session = null
        } catch (e: Exception) {
            Timber.e(e, "Error closing ARCore session")
        }
    }
}