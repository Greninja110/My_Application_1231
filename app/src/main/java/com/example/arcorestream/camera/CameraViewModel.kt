package com.example.arcorestream.camera

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.arcorestream.ar.ARCoreSession
import com.example.arcorestream.service.StreamingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val cameraManager: CameraManager,
    private val arCoreSession: ARCoreSession
) : ViewModel() {

    // State flows for UI
    private val _isCameraReady = MutableStateFlow(false)
    val isCameraReady: StateFlow<Boolean> = _isCameraReady.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _networkInfo = MutableStateFlow<Map<String, Any>>(emptyMap())
    val networkInfo: StateFlow<Map<String, Any>> = _networkInfo.asStateFlow()

    // Service connection
    private var streamingService: StreamingService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            bound = true

            // Observe service state flows
            viewModelScope.launch {
                streamingService?.isStreaming?.collect {
                    _isStreaming.value = it
                }
            }

            viewModelScope.launch {
                streamingService?.networkInfo?.collect {
                    _networkInfo.value = it
                }
            }

            Timber.d("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            bound = false
            Timber.d("Service disconnected")
        }
    }

    /**
     * Initialize camera system
     */
    fun initCamera(context: Context) {
        cameraManager.init(
            context = context,
            onSuccess = {
                _isCameraReady.value = true
                Timber.d("Camera initialized successfully")
            },
            onError = { e ->
                _errorMessage.value = "Failed to initialize camera: ${e.message}"
                Timber.e(e, "Failed to initialize camera")
            }
        )
    }

    /**
     * Start camera preview
     */
    fun startCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider
    ) {
        if (!_isCameraReady.value) {
            _errorMessage.value = "Camera not initialized"
            return
        }

        cameraManager.startPreview(
            context = context,
            lifecycleOwner = lifecycleOwner,
            surfaceProvider = surfaceProvider,
            onSuccess = {
                Timber.d("Camera preview started")
            },
            onError = { e ->
                _errorMessage.value = "Failed to start camera: ${e.message}"
                Timber.e(e, "Failed to start camera preview")
            }
        )
    }

    /**
     * Set surface provider for camera preview
     */
    fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        Timber.d("Setting surface provider")
        cameraManager.connectPreview(surfaceProvider)
    }

    /**
     * Switch between front and back camera
     */
    fun switchCamera(lifecycleOwner: LifecycleOwner) {
        cameraManager.switchCamera(lifecycleOwner)
    }

    /**
     * Bind to streaming service
     */
    fun bindService(context: Context) {
        Timber.d("Binding to streaming service")
        val intent = Intent(context, StreamingService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Unbind from streaming service
     */
    fun unbindService(context: Context) {
        if (bound) {
            context.unbindService(serviceConnection)
            bound = false
            Timber.d("Unbound from streaming service")
        }
    }

    /**
     * Start streaming
     */
    fun startStreaming(serverIP: String, serverPort: Int) {
        streamingService?.startStreaming(serverIP, serverPort)
        Timber.d("Start streaming requested to $serverIP:$serverPort")
    }

    /**
     * Stop streaming
     */
    fun stopStreaming() {
        streamingService?.stopStreaming()
        Timber.d("Stop streaming requested")
    }

    /**
     * Clean up resources
     */
    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
        arCoreSession.close()
        Timber.d("ViewModel cleared")
    }
}