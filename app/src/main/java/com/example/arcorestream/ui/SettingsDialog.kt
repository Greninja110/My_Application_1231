package com.example.arcorestream.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.example.arcorestream.R

class SettingsDialog(
    context: Context,
    private val defaultServerIP: String,
    private val defaultServerPort: Int,
    private val onStartStreaming: (serverIP: String, serverPort: Int) -> Unit
) : Dialog(context) {

    private lateinit var etServerIP: EditText
    private lateinit var etServerPort: EditText
    private lateinit var btnStart: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_settings)

        // Initialize views
        etServerIP = findViewById(R.id.etServerIP)
        etServerPort = findViewById(R.id.etServerPort)
        btnStart = findViewById(R.id.btnStartStreaming)
        btnCancel = findViewById(R.id.btnCancel)

        // Set default values
        etServerIP.setText(defaultServerIP)
        etServerPort.setText(defaultServerPort.toString())

        // Set up button listeners
        btnStart.setOnClickListener {
            startStreaming()
        }

        btnCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun startStreaming() {
        val serverIP = etServerIP.text.toString().trim()
        val serverPortStr = etServerPort.text.toString().trim()

        // Validate IP address
        if (!isValidIpAddress(serverIP)) {
            Toast.makeText(context, "Invalid IP address", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate port
        val serverPort = try {
            serverPortStr.toInt()
        } catch (e: Exception) {
            Toast.makeText(context, "Invalid port number", Toast.LENGTH_SHORT).show()
            return
        }

        if (serverPort <= 0 || serverPort > 65535) {
            Toast.makeText(context, "Port must be between 1 and 65535", Toast.LENGTH_SHORT).show()
            return
        }

        // Start streaming
        onStartStreaming(serverIP, serverPort)
        dismiss()
    }

    private fun isValidIpAddress(ip: String): Boolean {
        val pattern = "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})"
        val regex = Regex(pattern)

        if (!regex.matches(ip)) {
            return false
        }

        // Check each octet is in range 0-255
        val octets = ip.split(".")
        for (octet in octets) {
            val num = octet.toInt()
            if (num < 0 || num > 255) {
                return false
            }
        }

        return true
    }
}