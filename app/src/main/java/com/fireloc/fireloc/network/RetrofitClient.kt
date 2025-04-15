package com.fireloc.fireloc.network // Make sure package name is correct

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton object to configure and provide a Retrofit instance for API calls.
 */
object RetrofitClient {

    // IMPORTANT: Replace with the actual base URL of Arjun's deployed backend service
    // For local testing with emulator, it might be "http://10.0.2.2:PORT/"
    // For deployed function, it will be something like "https://your-region-your-project.cloudfunctions.net/"
    private const val BASE_URL = "http://YOUR_BACKEND_BASE_URL_HERE/" // <<< CHANGE THIS

    // Create a logging interceptor (optional, but very helpful for debugging)
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY // Log request/response bodies
    }

    // Configure OkHttpClient (optional: add timeouts, interceptors)
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor) // Add logging
        .connectTimeout(30, TimeUnit.SECONDS) // Example timeout
        .readTimeout(30, TimeUnit.SECONDS)    // Example timeout
        .writeTimeout(30, TimeUnit.SECONDS)   // Example timeout
        .build()

    // Configure Retrofit using lazy initialization
    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient) // Use custom OkHttpClient
            .addConverterFactory(GsonConverterFactory.create()) // Use Gson for JSON parsing
            .build()
    }

    // Provide an instance of the ApiService interface using lazy initialization
    val instance: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}