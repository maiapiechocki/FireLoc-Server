package com.fireloc.fireloc.network // Make sure package name is correct

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Defines the API endpoints for communication with the FireLoc backend.
 */
interface ApiService {

    /**
     * Registers a device with the backend.
     * Requires Firebase ID Token for user authentication.
     */
    @POST("/registerDevice") // Use the actual endpoint path provided by Arjun
    suspend fun registerDevice(
        @Header("Authorization") bearerToken: String, // e.g., "Bearer <FIREBASE_ID_TOKEN>"
        @Body registrationData: DeviceRegistrationRequest
    ): Response<DeviceRegistrationResponse> // Using Response allows checking success/error codes

    // TODO: Add the /detect endpoint definition later
    /*
    @POST("/detect")
    suspend fun detectFire(
         // @Header("X-Firebase-AppCheck") appCheckToken: String, // App Check token might be added automatically by SDKs or needed manually
         @Body detectRequest: DetectRequest
    ): Response<DetectResponse>
    */
}