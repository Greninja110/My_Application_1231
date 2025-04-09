package com.example.arcorestream.network

enum class ConnectionState {
    UNKNOWN,
    CONNECTED,
    DISCONNECTED,
    CONNECTING
}

enum class NetworkType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    BLUETOOTH,
    OTHER
}

enum class ConnectionQuality {
    UNKNOWN,
    EXCELLENT,
    GOOD,
    MODERATE,
    POOR,
    NO_INTERNET
}