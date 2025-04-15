package com.fireloc.fireloc.utils
import android.util.Log
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.renderscript.*
import java.lang.IllegalStateException

class YuvToRgbConverter(context: Context) {

    private val rs = RenderScript.create(context)
    private val scriptYuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))

    private var yuvInputAllocation: Allocation? = null
    private var rgbOutputAllocation: Allocation? = null
    private var lastWidth = -1
    private var lastHeight = -1
    private var yuvBytesCache: ByteArray? = null // Cache byte array

    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {
        val imageWidth = image.width
        val imageHeight = image.height

        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Input image is not in YUV_420_888 format")
        }

        // Re-create allocations only if size changes
        if (yuvInputAllocation == null || lastWidth != imageWidth || lastHeight != imageHeight) {
            val yuvType = Type.Builder(rs, Element.U8(rs)).setX(imageWidth).setY(imageHeight).setYuvFormat(ImageFormat.YUV_420_888)
            yuvInputAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT)

            val rgbType = Type.Builder(rs, Element.RGBA_8888(rs)).setX(imageWidth).setY(imageHeight)
            rgbOutputAllocation = Allocation.createTyped(rs, rgbType.create(), Allocation.USAGE_SCRIPT)

            yuvBytesCache = ByteArray(getYuvSize(image)) // Allocate cache based on actual size

            lastWidth = imageWidth
            lastHeight = imageHeight
            Log.d("YuvToRgbConverter", "Recreated allocations for size ${imageWidth}x${imageHeight}")
        }

        val yuvBytes = yuvBytesCache ?: throw IllegalStateException("Byte array cache not initialized")

        imageToByteArray(image, yuvBytes) // Fill the cached byte array

        yuvInputAllocation!!.copyFrom(yuvBytes)
        scriptYuvToRgb.setInput(yuvInputAllocation)
        scriptYuvToRgb.forEach(rgbOutputAllocation)
        rgbOutputAllocation!!.copyTo(output)
    }

    // Optimized function to copy Image planes to a single byte array (NV21 format needed by Script)
    private fun imageToByteArray(image: Image, outputBytes: ByteArray) {
        if (image.format != ImageFormat.YUV_420_888) {
            throw IllegalArgumentException("Unsupported image format")
        }
        val imageWidth = image.width
        val imageHeight = image.height
        val planes = image.planes

        var bufferOffset = 0

        // Y plane
        val yPlane = planes[0]
        val yRowStride = yPlane.rowStride
        val yBuffer = yPlane.buffer
        for (row in 0 until imageHeight) {
            yBuffer.position(row * yRowStride)
            val rowBytes = minOf(imageWidth, yBuffer.remaining())
            yBuffer.get(outputBytes, bufferOffset, rowBytes)
            bufferOffset += imageWidth // Always advance by width, not rowBytes, to handle padding
        }

        // U and V planes (interleaved for NV21)
        val uPlane = planes[1]
        val vPlane = planes[2]
        val uvPixelStride = uPlane.pixelStride // Should be same for U and V in YUV_420_888
        val uvRowStride = uPlane.rowStride
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (row in 0 until imageHeight / 2) {
            // Read the V row
            vBuffer.position(row * uvRowStride)
            // Read the U row
            uBuffer.position(row * uvRowStride)

            for (col in 0 until imageWidth / 2) {
                val vBufferIndex = row * uvRowStride + col * uvPixelStride
                val uBufferIndex = row * uvRowStride + col * uvPixelStride

                if (vBufferIndex < vBuffer.limit() && bufferOffset < outputBytes.size) {
                    outputBytes[bufferOffset++] = vBuffer[vBufferIndex]
                } else {
                    if(bufferOffset < outputBytes.size) outputBytes[bufferOffset++] = 0 // Pad if needed
                }
                if (uBufferIndex < uBuffer.limit() && bufferOffset < outputBytes.size) {
                    outputBytes[bufferOffset++] = uBuffer[uBufferIndex]
                } else {
                    if(bufferOffset < outputBytes.size) outputBytes[bufferOffset++] = 0 // Pad if needed
                }
            }
        }
    }

    // Calculate the expected size of the YUV_420_888 data
    private fun getYuvSize(image: Image): Int {
        val imageWidth = image.width
        val imageHeight = image.height
        val yBytes = imageWidth * imageHeight
        // UV plane has half the rows and half the columns, with 2 bytes per pixel (U and V)
        val uvBytes = imageWidth * imageHeight / 2
        return yBytes + uvBytes
    }
}