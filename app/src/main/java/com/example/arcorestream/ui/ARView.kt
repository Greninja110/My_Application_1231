package com.example.arcorestream.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.example.arcorestream.ar.ARCoreSession
import timber.log.Timber
import javax.inject.Inject

class ARView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    // ARCore session will be injected
    var arCoreSession: ARCoreSession? = null

    // State variables
    private var isReady = false

    // Listener for AR readiness
    var onARReady: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Timber.d("Surface texture available: $width x $height")

        // Configure ARCore with this surface
        arCoreSession?.let { session ->
            try {
                session.setCameraTexture(surface)
                isReady = true
                onARReady?.invoke()
                Timber.d("AR view setup complete")
            } catch (e: Exception) {
                Timber.e(e, "Failed to set up AR surface")
            }
        } ?: run {
            Timber.e("ARCore session is null")
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Timber.d("Surface texture size changed: $width x $height")
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Timber.d("Surface texture destroyed")
        isReady = false
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // This is called very frequently, so we don't log it
    }

    /**
     * Check if the AR view is ready
     */
    fun isARViewReady(): Boolean = isReady
}