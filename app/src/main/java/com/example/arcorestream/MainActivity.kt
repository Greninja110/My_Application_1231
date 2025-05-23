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
import com.google.ar.core.exceptions.UnavailableException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import androidx.camera.view.PreviewView

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

    // ARCore installation request code
    private val AR_CORE_INSTALL_REQUEST = 102
    private var installRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Timber.d("MainActivity onCreate")

        // Keep screen on during streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up UI listeners first - these don't require permissions
        setupUIListeners()

        // Show loading state
        binding.statusText.text = "Checking permissions..."
        binding.arStatusText.text = "ARCore: Checking..."

        // Request permissions
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE
            )
        } else {
            // Check ARCore availability first
            checkARCoreAndInitialize()
        }

        // Observe view model state
        observeViewModelState()
    }

    /**
     * Check ARCore availability and initialize components
     */
    private fun checkARCoreAndInitialize() {
        binding.statusText.text = "Checking ARCore..."

        try {
            when (ArCoreApk.getInstance().checkAvailability(this)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    Timber.d("ARCore is supported and installed")
                    binding.arStatusText.text = "ARCore: Ready"
                    initializeComponents()
                }
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    // Request installation
                    if (!installRequested) {
                        installRequested = true
                        requestARCoreInstall()
                    }
                }
                else -> {
                    Timber.e("ARCore not supported on this device")
                    binding.arStatusText.text = "ARCore: Not Supported"
                    Toast.makeText(
                        this,
                        "ARCore is not supported on this device",
                        Toast.LENGTH_LONG
                    ).show()
                    // Still allow camera streaming without AR
                    initializeComponents()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking ARCore availability")
            binding.arStatusText.text = "ARCore: Error"
            // Still allow camera streaming without AR
            initializeComponents()
        }
    }

    /**
     * Request ARCore installation
     */
    private fun requestARCoreInstall() {
        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(this, !installRequested)
            when (installStatus) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    binding.arStatusText.text = "ARCore: Installing..."
                    // Installation requested, will return to onResume after install
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    binding.arStatusText.text = "ARCore: Ready"
                    initializeComponents()
                }
            }
        } catch (e: UnavailableException) {
            Timber.e(e, "ARCore installation failed")
            binding.arStatusText.text = "ARCore: Install Failed"
            Toast.makeText(
                this,
                "Failed to install ARCore",
                Toast.LENGTH_LONG
            ).show()
            // Still allow camera streaming without AR
            initializeComponents()
        }
    }

    /**
     * Initialize ARCore and camera components
     */
    private fun initializeComponents() {
        binding.statusText.text = "Initializing..."

        try {
            // Initialize ARCore session
            val arInitialized = arCoreSession.initialize(this)
            if (arInitialized) {
                binding.arStatusText.text = "ARCore: Initialized"
                arCoreSession.setErrorCallback { e ->
                    Timber.e(e, "ARCore error")
                    runOnUiThread {
                        Toast.makeText(this, "ARCore error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                binding.arStatusText.text = "ARCore: Not Available"
            }

            // Initialize camera
            cameraViewModel.initCamera(this)

            // Bind to streaming service
            cameraViewModel.bindService(this)

            // Create the camera preview surface
            binding.previewView.post {
                startCameraPreview()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error initializing components")
            Toast.makeText(
                this,
                "Initialization error: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Start camera preview
    private fun startCameraPreview() {
        val surfaceProvider = binding.previewView.surfaceProvider
        cameraViewModel.startCamera(this, this, surfaceProvider)

        // Setup ARCore with camera texture
        binding.previewView.post {
            Timber.d("Camera preview is ready")
            binding.statusText.text = "Ready"
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
                checkARCoreAndInitialize()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted. The app may not function correctly.",
                    Toast.LENGTH_LONG
                ).show()
                binding.statusText.text = "Permissions Denied"
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Resume ARCore if it was initialized
        if (::arCoreSession.isInitialized) {
            arCoreSession.resume()
        }

        // Check if we're returning from ARCore installation
        if (installRequested) {
            checkARCoreAndInitialize()
        }
    }

    override fun onPause() {
        super.onPause()

        // Pause ARCore if it was initialized
        if (::arCoreSession.isInitialized) {
            arCoreSession.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraViewModel.unbindService(this)
    }
}