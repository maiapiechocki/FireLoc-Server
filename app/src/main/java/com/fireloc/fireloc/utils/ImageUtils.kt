package com.fireloc.fireloc.utils // Or com.fireloc.fireloc.camera if preferred

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image // <<< NEEDED for YuvToRgbConverter potentially
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Utilities for image conversions related to CameraX and processing.
 */
object ImageUtils {

    private const val TAG = "ImageUtils"

    /**
     * Converts an ImageProxy (typically YUV_420_888) to a Bitmap.
     *
     * It attempts to use YuvImage conversion for reliability.
     * Handles rotation based on the ImageProxy's rotationDegrees.
     *
     * Remember to close the source ImageProxy after calling this if needed.
     *
     * @param imageProxy The ImageProxy received from CameraX ImageAnalysis.
     * @return A Bitmap in RGB format, rotated correctly, or null if conversion fails.
     */
    @SuppressLint("UnsafeOptInUsageError") // Needed for ImageProxy.image access
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null // Get the underlying android.media.Image

        // Check if format is YUV_420_888 (most common)
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unsupported image format: ${image.format}. Expected YUV_420_888.")
            // Handle other formats if necessary (e.g., JPEG directly decode)
            // For JPEG:
            // if (image.format == ImageFormat.JPEG) {
            //    val buffer = image.planes[0].buffer
            //    val bytes = ByteArray(buffer.remaining())
            //    buffer.get(bytes)
            //    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            // }
            return null
        }

        // --- Use YuvImage for conversion (generally more reliable) ---
        return try {
            val nv21Bytes = yuv420ThreePlanesToNV21(image.planes, imageProxy.width, imageProxy.height)
            if (nv21Bytes == null) {
                Log.e(TAG, "Failed to convert YUV planes to NV21 byte array.")
                return null
            }

            val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out) // Adjust quality (80-95)
            val imageBytes = out.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // Rotate bitmap based on ImageProxy rotationDegrees info
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap // No rotation needed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting YUV_420_888 to bitmap via YuvImage: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Helper function to convert YUV_420_888 planes to a single NV21 byte array.
     * Handles planar (I420) and semi-planar (NV12) layouts.
     * Assumes Plane 0 is Y, Plane 1 is U (Cb), Plane 2 is V (Cr).
     */
    private fun yuv420ThreePlanesToNV21(
        planes: Array<Image.Plane>,
        width: Int,
        height: Int
    ): ByteArray? {
        val imageSize = width * height
        val nv21Bytes = ByteArray(imageSize + 2 * (imageSize / 4)) // Y plane + VU plane

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer: ByteBuffer = yPlane.buffer
        val uBuffer: ByteBuffer = uPlane.buffer
        val vBuffer: ByteBuffer = vPlane.buffer

        // Check row strides to ensure buffers are contiguous enough
        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        // 1. Copy Y Plane
        // If Y plane is contiguous (rowStride == width)
        if (yRowStride == width) {
            yBuffer.get(nv21Bytes, 0, imageSize)
        } else {
            // Copy row by row if not contiguous
            var yOffset = 0
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21Bytes, yOffset, width)
                yOffset += width
            }
        }

        // 2. Copy VU Plane (Interleaved)
        // NV21 format requires V plane data first, then U plane data, interleaved (VUVUVU...)
        // starting right after the Y plane. Output offset starts at imageSize.
        var vuOffset = imageSize
        // Check if U and V planes are interleaved (pixel stride 2) or planar (pixel stride 1)
        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == uRowStride) {
            // Likely NV12 or NV21 format already (semi-planar)
            // NV12: U buffer contains U, V buffer contains V (interleaved UV plane)
            // NV21: V buffer contains V, U buffer contains U (interleaved VU plane) - Requires V first
            val vuHeight = height / 2
            val vuWidth = width / 2

            // Copy V plane, then U plane, interleaving them into nv21Bytes[imageSize..]
            // This part is tricky and depends heavily on exact YUV format details
            // Let's copy V then U row by row, handling strides.

            // Buffer to hold V plane data
            val vBytes = ByteArray(vBuffer.remaining())
            vBuffer.get(vBytes)
            // Buffer to hold U plane data
            val uBytes = ByteArray(uBuffer.remaining())
            uBuffer.get(uBytes)

            for (row in 0 until vuHeight) {
                val vRowOffset = row * vRowStride
                val uRowOffset = row * uRowStride
                for (col in 0 until vuWidth) {
                    // Calculate index within the planar byte arrays
                    val vIndex = vRowOffset + col * vPixelStride
                    val uIndex = uRowOffset + col * uPixelStride

                    // Check bounds before accessing
                    if(vIndex < vBytes.size && uIndex < uBytes.size && vuOffset + 1 < nv21Bytes.size){
                        // NV21 order: V then U
                        nv21Bytes[vuOffset++] = vBytes[vIndex]
                        nv21Bytes[vuOffset++] = uBytes[uIndex]
                    } else {
                        // Log an error or break if indices go out of bounds
                        Log.w(TAG, "Potential index out of bounds during VU interleaving.")
                        break // Exit inner loop
                    }
                }
                if(vuOffset >= nv21Bytes.size) break // Exit outer loop if full
            }

        } else if (vPixelStride == 1 && uPixelStride == 1) {
            // Planar format (I420) - Y plane, then U plane, then V plane
            // We need to interleave U and V manually into the VU section of nv21Bytes
            val uBytes = ByteArray(uBuffer.remaining())
            uBuffer.get(uBytes)
            val vBytes = ByteArray(vBuffer.remaining())
            vBuffer.get(vBytes)

            val vuHeight = height / 2
            val vuWidth = width / 2
            var uIdx = 0
            var vIdx = 0

            for (row in 0 until vuHeight) {
                // These row strides might be different from yRowStride
                val uRowStart = row * uRowStride
                val vRowStart = row * vRowStride

                for (col in 0 until vuWidth) {
                    // Check bounds
                    if (vRowStart + col < vBytes.size && uRowStart + col < uBytes.size && vuOffset + 1 < nv21Bytes.size) {
                        nv21Bytes[vuOffset++] = vBytes[vRowStart + col] // V
                        nv21Bytes[vuOffset++] = uBytes[uRowStart + col] // U
                    } else {
                        Log.w(TAG, "Potential index out of bounds during planar VU interleaving.")
                        break
                    }
                }
                if(vuOffset >= nv21Bytes.size) break
            }

        } else {
            Log.e(TAG, "Unsupported YUV plane configuration: V stride ${vPixelStride}, U stride ${uPixelStride}")
            return null
        }

        return nv21Bytes
    }

} // End of ImageUtils object