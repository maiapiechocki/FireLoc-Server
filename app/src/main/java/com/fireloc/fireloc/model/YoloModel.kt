package com.fireloc.fireloc.model

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import ai.onnxruntime.OnnxTensor // Keep this
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtUtil // <<< Add this import for tensor data handling
import kotlin.math.max // <<< Add this import
import kotlin.math.min // <<< Add this import

class YoloModel(context: Context) {

    companion object {
        private const val TAG = "FireLocYoloModel"
        private const val MODEL_FILENAME = "best.onnx"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 2 // 0=no_fire/smoke, 1=fire
        private const val NUM_BOXES = 8400
    }

    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

    data class YoloDetection(
        val classId: Int,
        val confidence: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    init {
        try {
            val modelBytes = context.assets.open(MODEL_FILENAME).readBytes()
            ortSession = ortEnvironment.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i(TAG, "ONNX model initialized successfully from $MODEL_FILENAME")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ONNX model: ${e.message}")
            ortSession = null
        }
    }

    fun detect(bitmap: Bitmap): List<YoloDetection> {
        if (ortSession == null) {
            Log.e(TAG, "ONNX session is not initialized")
            return emptyList()
        }

        try {
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = preProcessImage(resizedBitmap)

            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()) // NCHW
            // Use OrtUtil.reshape for creating tensor from FloatBuffer
            val inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, shape)

            val results = ortSession?.run(mapOf("images" to inputTensor))

            val detections = results?.use { postProcessOutput(it) } ?: emptyList()

            inputTensor.close()

            return detections
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference: ${e.message}")
            e.printStackTrace() // Print stack trace for debugging
            return emptyList()
        }
    }

    private fun preProcessImage(bitmap: Bitmap): FloatBuffer {
        // (Keep preProcessImage the same as before)
        val imgData = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        imgData.rewind()
        val stride = INPUT_SIZE * INPUT_SIZE
        val bmpData = IntArray(stride)
        bitmap.getPixels(bmpData, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (i in 0 until stride) {
            val pixelValue = bmpData[i]
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f
            imgData.put(i, r); imgData.put(i + stride, g); imgData.put(i + stride * 2, b)
        }
        imgData.rewind()
        return imgData
    }

    // --- MODIFIED postProcessOutput ---
    private fun postProcessOutput(output: OrtSession.Result): List<YoloDetection> {
        try {
            // Safely get the output tensor value (might be FloatBuffer, Array, etc.)
            val outputValue = output.get(0)?.value ?: return emptyList<YoloDetection>().also {
                Log.e(TAG, "Output tensor value is null")
                output.close()
            }

            // --- Handle based on common ONNX output formats ---
            val outputData: Array<FloatArray> // Target: [NumOutputs][NumBoxes]

            // Case 1: Direct FloatArray output (less common for multi-dim)
            if (outputValue is FloatArray) {
                Log.w(TAG, "Output is flat FloatArray, attempting reshape.")
                // Reshape based on expected dimensions (assuming batch size 1)
                val numOutputsPerBox = 4 + NUM_CLASSES
                if (outputValue.size == numOutputsPerBox * NUM_BOXES) {
                    // Manually transpose [Outputs * Boxes] -> [Outputs][Boxes]
                    outputData = Array(numOutputsPerBox) { j ->
                        FloatArray(NUM_BOXES) { i ->
                            outputValue[j * NUM_BOXES + i]
                        }
                    }
                } else {
                    Log.e(TAG, "Output is FloatArray but size ${outputValue.size} doesn't match expected ${numOutputsPerBox * NUM_BOXES}")
                    return emptyList()
                }
            }
            // Case 2: Nested Array output (e.g., Array<Array<FloatArray>> for [Batch][Outputs][Boxes])
            else if (outputValue is Array<*> && outputValue.isNotEmpty() && outputValue[0] is Array<*> && (outputValue[0] as Array<*>).isNotEmpty() && (outputValue[0] as Array<*>)[0] is FloatArray) {
                // Assume shape [1, NumOutputs, NumBoxes]
                @Suppress("UNCHECKED_CAST") // We've checked the types
                val nestedArray = outputValue as Array<Array<FloatArray>>
                if (nestedArray[0].size == (4 + NUM_CLASSES) && nestedArray[0][0].size == NUM_BOXES) {
                    Log.d(TAG, "Output tensor shape appears to be [1, ${4+NUM_CLASSES}, $NUM_BOXES]")
                    outputData = nestedArray[0] // Use the data for the first batch item
                } else {
                    Log.e(TAG, "Output tensor shape [1, ${nestedArray[0].size}, ${nestedArray[0][0].size}] doesn't match expected [1, ${4+NUM_CLASSES}, $NUM_BOXES]")
                    return emptyList()
                }
            }
            // Case 3: FloatBuffer output
            else if (outputValue is FloatBuffer) {
                Log.w(TAG, "Output is FloatBuffer, reading data.")
                val buffer = outputValue
                buffer.rewind()
                val expectedSize = (4 + NUM_CLASSES) * NUM_BOXES
                if (buffer.remaining() == expectedSize) {
                    outputData = Array(4 + NUM_CLASSES) { j ->
                        FloatArray(NUM_BOXES) { i ->
                            buffer.get(j * NUM_BOXES + i) // Read transposed
                        }
                    }
                } else {
                    Log.e(TAG, "Output FloatBuffer size ${buffer.remaining()} doesn't match expected ${expectedSize}")
                    return emptyList()
                }
            } else {
                Log.e(TAG, "Output tensor has unexpected type: ${outputValue.javaClass.name}")
                return emptyList()
            }

            // --- Processing (assuming outputData is now [NumOutputs][NumBoxes]) ---
            val detections = mutableListOf<YoloDetection>()
            val confidenceThreshold = 0.25f
            val numOutputsPerBox = 4 + NUM_CLASSES

            // Transpose outputData [NumOutputs][NumBoxes] -> [NumBoxes][NumOutputs]
            val transposedOutput = Array(NUM_BOXES) { i ->
                FloatArray(numOutputsPerBox) { j ->
                    outputData[j][i] // Read transposed
                }
            }

            for (i in 0 until NUM_BOXES) {
                val boxData = transposedOutput[i] // [cx, cy, w, h, cls0_score, cls1_score]
                val scores = boxData.sliceArray(4 until numOutputsPerBox)

                // --- FIXED Destructuring ---
                // Find index and value of max score
                val maxScoreResult: IndexedValue<Float>? = scores.withIndex().maxByOrNull { it.value }
                val maxClassId = maxScoreResult?.index ?: -1 // Get index or -1 if null
                val maxScore = maxScoreResult?.value ?: 0f   // Get value or 0f if null
                // --- END FIXED Destructuring ---

                if (maxScore >= confidenceThreshold && maxClassId != -1) {
                    val cx = boxData[0]
                    val cy = boxData[1]
                    val w = boxData[2]
                    val h = boxData[3]

                    val left = (cx - w / 2f) / INPUT_SIZE
                    val top = (cy - h / 2f) / INPUT_SIZE
                    val right = (cx + w / 2f) / INPUT_SIZE
                    val bottom = (cy + h / 2f) / INPUT_SIZE

                    // Use kotlin.math.max/min which are equivalent to maxOf/minOf for floats
                    val clampedLeft = max(0f, left).coerceAtMost(1f) // More concise clamping
                    val clampedTop = max(0f, top).coerceAtMost(1f)
                    val clampedRight = max(0f, right).coerceAtMost(1f)
                    val clampedBottom = max(0f, bottom).coerceAtMost(1f)


                    if (clampedRight > clampedLeft && clampedBottom > clampedTop) {
                        detections.add(
                            YoloDetection(maxClassId, maxScore, clampedLeft, clampedTop, clampedRight, clampedBottom)
                        )
                    }
                }
            }

            Log.d(TAG, "Raw detections before NMS: ${detections.size}")
            val nmsResults = applyNms(detections)
            Log.d(TAG, "Detections after NMS: ${nmsResults.size}")
            return nmsResults

        } catch (e: Exception) {
            Log.e(TAG, "Error during post-processing: ${e.message}")
            e.printStackTrace()
            return emptyList()
        } finally {
            try { // Add try-catch around close just in case
                output.close()
            } catch (closeE: Exception) {
                Log.e(TAG, "Error explicitly closing OrtSession.Result: ${closeE.message}")
            }
        }
    }


    // --- applyNms and calculateIou remain the same ---
    private fun applyNms(detections: List<YoloDetection>, iouThreshold: Float = 0.45f): List<YoloDetection> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<YoloDetection>()
        val isActive = BooleanArray(sortedDetections.size) { true }

        for (i in sortedDetections.indices) {
            if (!isActive[i]) continue
            selectedDetections.add(sortedDetections[i])
            for (j in (i + 1) until sortedDetections.size) {
                if (isActive[j] && sortedDetections[i].classId == sortedDetections[j].classId) {
                    val iou = calculateIou(sortedDetections[i], sortedDetections[j])
                    if (iou > iouThreshold) {
                        isActive[j] = false
                    }
                }
            }
        }
        return selectedDetections
    }

    private fun calculateIou(a: YoloDetection, b: YoloDetection): Float {
        // Use kotlin.math.max/min
        val xOverlap = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val yOverlap = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = xOverlap * yOverlap
        val epsilon = 1e-7f
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection + epsilon
        return intersection / union
    }

    // --- close function remains the same ---
    fun close() {
        try {
            ortSession?.close()
            ortEnvironment.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX resources: ${e.message}")
        }
    }
}