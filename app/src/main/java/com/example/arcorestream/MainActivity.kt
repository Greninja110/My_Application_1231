package com.example.arcorestream

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.arcorestream.ar.ARCoreSession
import com.example.arcorestream.camera.CameraViewModel
import com.example.arcorestream.databinding.ActivityMainBinding
import com.example.arcorestream.ui.SettingsDialog
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cameraViewModel: CameraViewModel by viewModels()

    @Inject lateinit var arCoreSession: ARCoreSession

    // Default server settings
    private val defaultServerIP = "192.168.1.100"  // Change this to your laptop's IP
    private val defaultServerPort = 5000

    // Permissions
    private val PERMISSIONS_REQUEST_CODE = 101
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.INTERNET,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check ARCore availability
        checkARCoreAvailability()

        // Request permissions
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE
            )
        } else {
            // Initialize components
            initializeComponents()
        }

        // Set up UI listeners
        setupUIListeners()

        // Observe view model state
        observeViewModelState()
    }

    /**
     * Check if ARCore is available on this device
     */
    private fun checkARCoreAvailability() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
            Toast.makeText(
                this,
                "ARCore not available. Please install ARCore from Play Store.",
                Toast.LENGTH_LONG
            ).show()
            Timber.e("ARCore not available: $availability")
        }
    }

    /**
     * Initialize ARCore and camera components
     */
    private fun initializeComponents() {
        // Initialize ARCore session
        arCoreSession.initialize(this)
        arCoreSession.setErrorCallback { e ->
            Timber.e(e, "ARCore error")
            Toast.makeText(this, "ARCore error: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        // Initialize camera
        cameraViewModel.initCamera(this)

        // Bind to streaming service
        cameraViewModel.bindService(this)

        // Create the camera preview surface
        binding.previewView.post {
            startCameraPreview()
        }
    }

    /**
     * Start camera preview
     */
    private fun startCameraPreview() {
        val surfaceProvider = binding.previewView.surfaceProvider
        cameraViewModel.startCamera(this, this, surfaceProvider)

        // Setup ARCore with camera texture
        binding.previewView.previewStreamState.observe(this) { state ->
            if (state == Preview.StreamState.STREAMING) {
                Timber.d("Camera preview is streaming")
            }
        }
    }

    /**
     * Set up UI listeners
     */
    private fun setupUIListeners() {
        // Start/stop streaming button
        binding.btnToggleStreaming.setOnClickListener {
            if (cameraViewModel.isStreaming.value) {
                cameraViewModel.stopStreaming()
            } else {
                showSettingsDialog()
            }
        }

        // Switch camera button
        binding.btnSwitchCamera.setOnClickListener {
            cameraViewModel.switchCamera(this)
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    /**
     * Show settings dialog for server configuration
     */
    private fun showSettingsDialog() {
        val settingsDialog = SettingsDialog(
            this,
            defaultServerIP,
            defaultServerPort
        ) { serverIP, serverPort ->
            // Start streaming with entered settings
            cameraViewModel.startStreaming(serverIP, serverPort)
        }

        settingsDialog.show()
    }

    /**
     * Observe view model state for UI updates
     */
    private fun observeViewModelState() {
        // Observe streaming state
        lifecycleScope.launch {
            cameraViewModel.isStreaming.collectLatest { isStreaming ->
                binding.btnToggleStreaming.text = if (isStreaming) "Stop Streaming" else "Start Streaming"
                binding.statusText.text = if (isStreaming) "Streaming" else "Ready"
                binding.statusIndicator.setBackgroundResource(
                    if (isStreaming) R.drawable.status_streaming else R.drawable.status_ready
                )
            }
        }

        // Observe network info
        lifecycleScope.launch {
            cameraViewModel.networkInfo.collectLatest { info ->
                if (info.isNotEmpty()) {
                    val streamUrl = info["stream_url"] as? String ?: "N/A"
                    val uploadBandwidth = info["upload_bandwidth_kbps"] as? Int ?: 0
                    val connectionQuality = info["connection_quality"] as? String ?: "UNKNOWN"

                    binding.infoText.text = "URL: $streamUrl\nBandwidth: ${uploadBandwidth / 1000} Mbps\nQuality: $connectionQuality"
                }
            }
        }

        // Observe error messages
        lifecycleScope.launch {
            cameraViewModel.errorMessage.collectLatest { errorMsg ->
                if (errorMsg != null) {
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Check if all required permissions are granted
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (allPermissionsGranted()) {
                initializeComponents()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted. The app may not function correctly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        arCoreSession.resume()
    }

    override fun onPause() {
        super.onPause()
        arCoreSession.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraViewModel.unbindService(this)
    }
}