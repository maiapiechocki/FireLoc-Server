package com.fireloc.fireloc.network // Make sure package name is correct

/**
 * Data class for the request body of the /registerDevice endpoint.
 */
data class DeviceRegistrationRequest(
    val deviceId: String,
    val deviceName: String? // Optional user-friendly name
)

/**
 * Data class for the success response body of the /registerDevice endpoint.
 */
data class DeviceRegistrationResponse(
    val status: String, // e.g., "success"
    val message: String // e.g., "Device registered successfully."
)

// TODO: Add data classes for /detect endpoint later
/*
data class LocationData(val latitude: Double, val longitude: Double) // Changed to Double

data class DetectRequest(
    val deviceId: String,
    val image_base64: String,
    val timestamp_ms: Long, // Changed to Long
    val location: LocationData,
    val mobile_detected: Boolean
)

data class DetectionResult(
    val class_id: Int,
    val confidence: Float, // Changed to Float
    val box_normalized: List<Float> // Changed to List<Float>
)

data class DetectResponse(
    val status: String?, // Make nullable for error cases?
    val detected: Boolean?, // Make nullable for error cases?
    val results: List<DetectionResult>?, // Make nullable for error cases?
    val error: String? // Field for error message
)
*/