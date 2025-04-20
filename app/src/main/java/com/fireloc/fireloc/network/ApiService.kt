package com.fireloc.fireloc.network // Ensure this package name is correct

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

interface ApiService {

    // Device Registration
    @POST
    suspend fun registerDevice(
        @Url url: String, // Full URL for registration
        @Header("Authorization") authToken: String,
        @Body registrationData: DeviceRegistrationRequest
    ): Response<Unit> // Expecting HTTP status code for success/failure

    // **** ADDED: Detection Endpoint ****
    @POST
    suspend fun detect(
        @Url url: String, // Full URL for detection
        @Header("Authorization") authToken: String,
        @Body detectRequestData: DetectRequest
    ): Response<DetectResponse> // Expecting a response body defined by DetectResponse

}