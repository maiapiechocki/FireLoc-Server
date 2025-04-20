package com.fireloc.fireloc.network

import com.google.gson.annotations.SerializedName

data class DeviceRegistrationRequest(
    @SerializedName("deviceId") // Ensure names match backend expectations if needed
    val deviceId: String,

    @SerializedName("deviceName")
    val deviceName: String? = null // Optional name
)