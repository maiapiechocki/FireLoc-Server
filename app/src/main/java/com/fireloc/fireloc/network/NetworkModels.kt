package com.fireloc.fireloc.network

import com.google.gson.annotations.SerializedName

// --- Models for the /detect endpoint ---

/**
 * Data class for the request body of the /detect endpoint.
 * Matches the structure expected by the backend guideline.
 */
data class DetectRequest(
    val deviceId: String,
    val image_base64: String, // Base64 encoded image string
    val timestamp_ms: Long, // Timestamp when image was captured/sent
    val location: LocationData?, // Device location (nullable if not available)
    val mobile_detected: Boolean // Flag indicating if mobile detection triggered send
)

/**
 * Represents the location data within the DetectRequest.
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null // Optional accuracy
)


/**
 * Represents a single detection result from the backend's cloud model.
 * Matches the structure expected by the backend guideline.
 */
data class DetectionResult(
    val class_id: Int, // 0 for smoke, 1 for fire (confirm with Arjun)
    val confidence: Float,
    val box_normalized: List<Float> // [left, top, right, bottom] normalized 0.0-1.0
)

/**
 * Data class for the response body of the /detect endpoint.
 * Nullable fields allow handling potential errors or cases where detection didn't run.
 * Matches the structure expected by the backend guideline.
 */
data class DetectResponse(
    val status: String?, // e.g., "processed", "error"
    val detected: Boolean?, // Was anything detected by the cloud model?
    val results: List<DetectionResult>?, // List of detections if detected=true
    val error: String? // Error message if status is "error"
)


/**
 * Data class for the response body of the /registerDevice endpoint (if it sends one).
 * Currently, we expect only success/failure via HTTP status code, but defining
 * this is good practice in case the backend adds a response body later.
 */
data class DeviceRegistrationResponse(
    val status: String, // e.g., "success", "already_registered", "error"
    val message: String? = null
)

// --- REMOVED duplicate RetrofitClient object ---
// object RetrofitClient { // <-- DELETE THIS BLOCK
//     // Placeholder - The actual implementation is in ApiClient.kt
// }