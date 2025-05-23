package com.example.arcorestream.ar

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
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
    private var cameraTextureId: Int = -1

    // Latest frame data
    private var latestFrame: Frame? = null
    private var depthImage: Image? = null

    // Error listener
    private var errorCallback: ((Exception) -> Unit)? = null

    /**
     * Initializes the AR session with the activity context
     */
    fun initialize(activity: Activity): Boolean {
        try {
            Timber.d("Initializing ARCore session...")

            // Check AR availability before creating session
            when (ArCoreApk.getInstance().checkAvailability(activity)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    Timber.d("ARCore is supported and installed")
                }
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    // Request ARCore installation
                    try {
                        when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                                Timber.d("ARCore installation requested")
                                return false
                            }
                            ArCoreApk.InstallStatus.INSTALLED -> {
                                Timber.d("ARCore installed")
                            }
                        }
                    } catch (e: UnavailableException) {
                        Timber.e(e, "ARCore installation failed")
                        errorCallback?.invoke(e)
                        return false
                    }
                }
                else -> {
                    Timber.e("ARCore not supported on this device")
                    errorCallback?.invoke(Exception("ARCore not supported"))
                    return false
                }
            }

            // Create session safely
            try {
                session = Session(activity)
                Timber.d("ARCore session created successfully")

                // Create texture for camera
                createCameraTexture()

                // Configure session
                configureSession()

                return true
            } catch (e: Exception) {
                Timber.e(e, "Failed to create ARCore session")
                errorCallback?.invoke(e)
                return false
            }
        } catch (e: Exception) {
            Timber.e(e, "Fatal error initializing ARCore")
            errorCallback?.invoke(e)
            return false
        }
    }

    /**
     * Creates OpenGL texture for camera feed
     */
    private fun createCameraTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        Timber.d("Created camera texture with ID: $cameraTextureId")
    }

    /**
     * Configure ARCore session with depth
     */
    private fun configureSession() {
        session?.let { arSession ->
            config = Config(arSession)

            // Enable depth API if available
            if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config?.depthMode = Config.DepthMode.AUTOMATIC
                Timber.d("Depth mode enabled")
            } else {
                Timber.w("Depth mode not supported on this device")
            }

            // Enable plane detection
            config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

            // Enable light estimation for better AR experience
            config?.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR

            // Set focus mode
            config?.focusMode = Config.FocusMode.AUTO

            // Update config
            arSession.configure(config)

            // Set the texture
            if (cameraTextureId != -1) {
                arSession.setCameraTextureName(cameraTextureId)
            }

            Timber.d("ARCore session configured")
        }
    }

    /**
     * Sets the surface texture for camera preview
     */
    fun setCameraTexture(texture: SurfaceTexture) {
        this.cameraTexture = texture

        // Clean up old surface
        cameraSurface?.release()

        // Create new surface
        cameraSurface = Surface(texture)

        // Attach to our texture ID if valid
        if (cameraTextureId != -1) {
            texture.attachToGLContext(cameraTextureId)
            Timber.d("Camera texture attached to GL context")
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
        if (isSessionPaused.get() || session == null) {
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
                        if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthImage = frame.acquireDepthImage16Bits()
                        }
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

            // Detach texture if needed
            cameraTexture?.detachFromGLContext()

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