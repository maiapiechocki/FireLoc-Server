package com.fireloc.fireloc.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface ApiService {

    // Use @Url because the base URL might not be common or easily determined
    // for separate Cloud Functions/Run services. Pass the full URL here.
    @POST // POST to the URL passed in the 'url' parameter
    suspend fun registerDevice(
        @Url url: String, // Pass the full registration URL here
        @Header("Authorization") authToken: String, // Format: "Bearer <ID_TOKEN>"
        @Body registrationData: DeviceRegistrationRequest
    ): Response<Unit> // Expects simple success (2xx) or failure (4xx, 5xx)

    // Add other endpoints here later if needed, potentially using @Url as well
    // e.g., suspend fun sendDetectionData(@Url url: String, ...)
}