package com.fireloc.fireloc.network // Ensure this package name is correct

import com.google.gson.annotations.SerializedName

// --- Models for /detect endpoint ---

data class DetectRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("image_base64") val imageBase64: String,
    @SerializedName("timestamp_ms") val timestampMs: Long,
    @SerializedName("location") val location: LocationData?,
    @SerializedName("mobile_detected") val mobileDetected: Boolean
)

data class LocationData(
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("accuracy") val accuracy: Float? = null
)

data class DetectResponse(
    @SerializedName("status") val status: String?,
    @SerializedName("detected") val detected: Boolean?,
    @SerializedName("results") val results: List<DetectionResult>?,
    @SerializedName("error") val error: String?
)

data class DetectionResult(
    @SerializedName("class_id") val classId: Int,
    @SerializedName("confidence") val confidence: Float,
    @SerializedName("box_normalized") val boxNormalized: List<Float> // [l,t,r,b]
)

// --- Models for /registerDevice endpoint ---

/**
 * Data class representing the JSON body for the registration request.
 * This is the SINGLE definition now.
 */
data class DeviceRegistrationRequest(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String? = null // Optional name
)

// Optional: Response for registration if needed later
// data class DeviceRegistrationResponse(...)