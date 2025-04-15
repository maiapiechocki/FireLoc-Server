package com.fireloc.fireloc.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.fireloc.fireloc.model.YoloModel
import com.fireloc.fireloc.utils.ImageUtils
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Processes camera frames (ImageProxy) for fire/smoke detection using the YOLO model.
 */
class DetectionProcessor(
    private val context: Context,
    private val onDetectionResult: (List<Detection>, Int, Int) -> Unit // Callback with results AND image dimensions
) {
    companion object {
        private const val TAG = "FireLocDetectionProc"
        // Keep threshold reasonable, adjust based on testing
        private const val DETECTION_THRESHOLD = 0.40f // You can tune this (e.g., 0.40f - 0.55f)
    }

    // Model for inference
    private val model: YoloModel = YoloModel(context) // Assumes YoloModel loads 'best.onnx'

    // Executor for running inference in the background
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Performance metrics & frame skipping
    private var lastProcessingTimeMs: Long = 0
    private var frameCounter = 0
    // --- MODIFICATION: Increase frame skipping ---
    // Process 1 out of every 5 frames (adjust as needed for your device speed)
    // If inference takes ~200ms, processing 1/5 frames gives it ~333ms budget at 30fps
    private val skipFrames = 4 // <<< INCREASED from 2 (means process 1 out of 5)
    // --- END MODIFICATION ---

    /**
     * Processes an ImageProxy from CameraX.
     * Converts it to a Bitmap and passes it to the detection model.
     * Runs detection on a background thread.
     * MUST call imageProxy.close() when done.
     */
    @SuppressLint("UnsafeOptInUsageError") // Needed for ImageProxy.image
    fun processImageProxy(imageProxy: ImageProxy) {
        frameCounter++

        // --- Frame Skipping Logic ---
        if (frameCounter % (skipFrames + 1) != 0) {
            imageProxy.close() // IMPORTANT: Must close the ImageProxy you don't process
            return
        }
        // --- End Frame Skipping ---

        val startTime = SystemClock.elapsedRealtime()

        // Capture image dimensions *before* closing proxy
        val imageWidth = imageProxy.width
        val imageHeight = imageProxy.height
        // Capture rotation degrees for coordinate transformation later
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // --- Convert ImageProxy to Bitmap ---
        // Pass rotationDegrees to ImageUtils if it uses it for intermediate steps,
        // although the final rotation should happen based on the proxy info later.
        // We primarily need the bitmap content correctly oriented *for the model*.
        // ImageUtils already includes rotation based on imageProxy.imageInfo.rotationDegrees
        val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)

        // IMPORTANT: Close the ImageProxy AFTER converting to Bitmap or if conversion fails
        imageProxy.close()

        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ImageProxy to bitmap")
            // Optionally inform UI that processing failed for this frame
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Pass empty list and original image dimensions
                onDetectionResult(emptyList(), imageWidth, imageHeight)
            }
            return // Skip processing if conversion failed
        }

        // --- Submit to Background Executor for Detection ---
        executor.execute {
            val inferenceStartTime = SystemClock.elapsedRealtime()
            var detections: List<YoloModel.YoloDetection> = emptyList()
            try {
                // Perform inference on the potentially rotated bitmap from ImageUtils
                detections = model.detect(bitmap) // Model receives bitmap oriented for it

                // Filter detections by confidence threshold
                val filteredDetections = filterDetections(detections)

                // Calculate processing time
                val endTime = SystemClock.elapsedRealtime()
                lastProcessingTimeMs = endTime - inferenceStartTime
                val totalTime = endTime - startTime

                // Log less frequently
                if (frameCounter % 15 == 0) { // Log approx every half second
                    Log.d(TAG, "Processed frame ${frameCounter}: " +
                            "Found ${filteredDetections.size} detections. " +
                            "Inference Time: $lastProcessingTimeMs ms. " +
                            "Total Time: $totalTime ms.")
                }

                // Post results back to main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    // Pass filtered detections AND the original image dimensions
                    onDetectionResult(filteredDetections, imageWidth, imageHeight)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during model detection/processing: ${e.message}")
                e.printStackTrace()
                // Post an empty result on error
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onDetectionResult(emptyList(), imageWidth, imageHeight) // Pass empty list
                }
            }
            // Bitmap recycling is usually handled by GC
            // finally { if (!bitmap.isRecycled) { bitmap.recycle() } }
        }
    }

    /**
     * Filters raw YOLO detections based on confidence and maps to our Detection data class.
     * Returns Detection objects with NORMALIZED coordinates (0.0 - 1.0) relative
     * to the input image dimensions used for inference (e.g., 640x640).
     */
    private fun filterDetections(rawDetections: List<YoloModel.YoloDetection>): List<Detection> {
        val filtered = rawDetections
            .filter { it.confidence >= DETECTION_THRESHOLD }
            .mapNotNull { yoloDetection ->
                // Basic sanity check on coordinates (should be 0.0-1.0 range relative to model input)
                if (yoloDetection.left < 0.0f || yoloDetection.top < 0.0f ||
                    yoloDetection.right > 1.0f || yoloDetection.bottom > 1.0f ||
                    yoloDetection.left >= yoloDetection.right || yoloDetection.top >= yoloDetection.bottom) {
                    Log.w(TAG, "Skipping detection with invalid normalized coords: $yoloDetection")
                    null // Skip this detection
                } else {
                    // Map class ID (Verify your training: 0=smoke, 1=fire)
                    val type = when (yoloDetection.classId) {
                        1 -> FireDetectionType.FIRE
                        0 -> FireDetectionType.SMOKE
                        else -> {
                            Log.w(TAG, "Skipping detection with unknown class ID: ${yoloDetection.classId}")
                            null
                        }
                    }

                    type?.let { // Only create Detection if type is valid
                        Detection(
                            type = it,
                            confidence = yoloDetection.confidence,
                            boundingBox = RectF( // Store NORMALIZED coords from model output
                                yoloDetection.left,
                                yoloDetection.top,
                                yoloDetection.right,
                                yoloDetection.bottom
                            )
                        )
                    }
                }
            }
        // NMS is handled within YoloModel.postProcessOutput
        return filtered
    }


    /**
     * Releases resources held by the processor (model, executor).
     */
    fun release() {
        try {
            executor.shutdown()
            model.close() // Ensure YoloModel's close is called
            Log.d(TAG, "DetectionProcessor resources released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing DetectionProcessor resources: ${e.message}")
        }
    }

    /**
     * Gets the last frame processing time (inference + post-processing) in milliseconds.
     */
    fun getLastProcessingTimeMs(): Long {
        return lastProcessingTimeMs
    }
}