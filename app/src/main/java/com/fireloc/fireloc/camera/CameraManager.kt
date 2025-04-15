package com.fireloc.fireloc.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages camera setup and lifecycle using CameraX.
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "FireLocCameraXManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    /**
     * Starts the camera preview and analysis stream.
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        analyzer: ImageAnalysis.Analyzer
    ) {
        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            // CameraProvider is now guaranteed to be available
            cameraProvider = cameraProviderFuture.get()

            // Set up the Preview use case
            preview = Preview.Builder()
                // Consider setting target resolution for consistency if needed
                .setTargetResolution(Size(1280, 720)) // Example resolution
                .build()
                .also {
                    it.setSurfaceProvider(surfaceProvider)
                }

            // Set up the ImageAnalysis use case
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720)) // Match preview if possible
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Drop frames if processing is slow
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, analyzer)
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to camera
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalyzer
                )
                Log.i(TAG, "CameraX use cases bound successfully")

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Releases camera resources.
     */
    fun releaseCamera() {
        try {
            cameraProvider?.unbindAll() // Unbind all use cases
            if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
                cameraExecutor.shutdown()
            }
            Log.i(TAG, "CameraX resources released")
        } catch(e: Exception) {
            Log.e(TAG, "Error releasing CameraX resources", e)
        }
    }
}