package com.example.arcorestream.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraManager @Inject constructor() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Target resolution
    private val targetResolution = Size(1280, 720)  // 720p for good balance of quality and performance

    /**
     * Initializes the camera system
     */
    fun init(context: Context, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    Timber.d("Camera provider initialized")
                    onSuccess()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to initialize camera provider")
                    onError(e)
                }
            }, cameraExecutor)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize camera manager")
            onError(e)
        }
    }

    /**
     * Starts the camera preview
     */
    fun startPreview(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            // Check if camera provider is available
            val provider = cameraProvider
            if (provider == null) {
                onError(IllegalStateException("Camera provider not initialized"))
                return
            }

            // Unbind any existing use cases
            provider.unbindAll()

            // Create preview use case
            previewUseCase = Preview.Builder()
                .build()

            // Set surface provider
            previewUseCase?.setSurfaceProvider(surfaceProvider)

            // Bind to lifecycle
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                previewUseCase
            )

            Timber.d("Camera preview started")
            onSuccess()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start camera preview")
            onError(e)
        }
    }

    /**
     * Connects the surface provider to the preview
     */
    fun connectPreview(surfaceProvider: Preview.SurfaceProvider) {
        if (previewUseCase != null) {
            previewUseCase?.setSurfaceProvider(surfaceProvider)
            Timber.d("Surface provider connected to preview")
        } else {
            Timber.e("Cannot connect surface provider - preview not initialized")
        }
    }

    /**
     * Switches between front and back camera
     */
    fun switchCamera(lifecycleOwner: LifecycleOwner) {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            Timber.d("Switching to front camera")
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            Timber.d("Switching to back camera")
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Rebind with new selector
        cameraProvider?.let { provider ->
            try {
                // Unbind all use cases
                provider.unbindAll()

                // Rebind with new selector
                if (previewUseCase != null) {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        previewUseCase
                    )
                }

                Timber.d("Camera switched successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to switch camera")
            }
        }
    }

    /**
     * Stops the camera preview
     */
    fun stopPreview() {
        try {
            cameraProvider?.unbindAll()
            Timber.d("Camera preview stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping camera preview")
        }
    }

    /**
     * Release resources
     */
    fun release() {
        try {
            previewUseCase = null
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
            Timber.d("Camera manager released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing camera manager")
        }
    }
}