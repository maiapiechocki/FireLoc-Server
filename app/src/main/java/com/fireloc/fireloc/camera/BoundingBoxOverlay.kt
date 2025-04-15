package com.fireloc.fireloc.camera

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
// Removed unused import: import android.util.Log
import android.view.View
import androidx.core.graphics.toColorInt // Import KTX extension

// --- Definitions Re-added ---
/**
 * Enum class for fire detection types
 */
enum class FireDetectionType {
    SMOKE,
    FIRE
}

/**
 * Data class representing a fire/smoke detection result
 * The boundingBox RectF is expected to be in the coordinate system needed by the consumer.
 * For BoundingBoxOverlay, this should be View Coordinates.
 * For DetectionProcessor output, this should be Normalized Coordinates.
 */
data class Detection(
    val type: FireDetectionType,
    val confidence: Float,
    val boundingBox: RectF // Coordinates depend on context (Normalized or View)
)
// --- End Definitions Re-added ---


/**
 * Custom view for drawing bounding boxes over detected fire/smoke.
 * Receives Detection objects with boundingBox RectF already in View Coordinates.
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Companion object is fine even if TAG is unused for now
    companion object {
        private const val TAG = "BoundingBoxOverlay"
    }

    // Constants for drawing
    private val textSize = 40f
    private val strokeWidth = 5f
    private val textPadding = 5f

    // List of current detections with coordinates already transformed for this view
    private var detectionsInViewCoords = emptyList<Detection>()

    // Paint objects for drawing
    private val firePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = this@BoundingBoxOverlay.strokeWidth
        isAntiAlias = true
    }

    private val smokePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = this@BoundingBoxOverlay.strokeWidth
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = this@BoundingBoxOverlay.textSize
        isAntiAlias = true
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val textBgPaint = Paint().apply {
        // Use KTX extension function as suggested by warning
        color = "#A0000000".toColorInt() // Semi-transparent black
        style = Paint.Style.FILL
    }

    /**
     * Updates the list of detections (expected in View Coordinates) and triggers a redraw
     */
    fun updateDetections(newDetections: List<Detection>) {
        this.detectionsInViewCoords = newDetections
        invalidate() // Trigger redraw
    }

    /**
     * Draws the bounding boxes and labels on the overlay.
     * Assumes the RectF in each Detection object is ALREADY in View Coordinates.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detectionsInViewCoords.isEmpty()) return

        for (detection in detectionsInViewCoords) {
            // Use the boundingBox directly!
            val rect = detection.boundingBox

            // Ensure rect has valid dimensions before drawing
            if (rect.width() <= 0 || rect.height() <= 0 || rect.left >= width || rect.top >= height || rect.right <= 0 || rect.bottom <= 0) {
                continue
            }

            // Choose paint based on detection type - Added 'else' branch
            val paint = when (detection.type) {
                FireDetectionType.FIRE -> firePaint
                FireDetectionType.SMOKE -> smokePaint
                // else is technically not needed if the enum only has two values,
                // but adding it makes the compiler happy and handles potential future additions.
                // else -> smokePaint // Default to smoke paint or handle error
            }

            // Draw the bounding box
            canvas.drawRect(rect, paint)

            // Prepare text to display (type and confidence)
            val label = "${detection.type.name}: ${(detection.confidence * 100).toInt()}%"
            val textBounds = Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val textWidth = textPaint.measureText(label)
            val textHeight = textBounds.height()

            // Calculate text background position (prefer above the box)
            val textBgTop = rect.top - textHeight - (2 * textPadding)
            val textBgBottom = rect.top

            // Adjust if text background would go off the top of the screen
            val finalBgTop: Float
            val finalBgBottom: Float
            val textY: Float

            if (textBgTop < 0) {
                finalBgTop = rect.top
                finalBgBottom = rect.top + textHeight + (2 * textPadding)
                textY = finalBgTop + textHeight + textPadding
            } else {
                finalBgTop = textBgTop
                finalBgBottom = textBgBottom
                textY = finalBgBottom - textPadding
            }

            val textBg = RectF(
                rect.left,
                finalBgTop,
                rect.left + textWidth + (2 * textPadding),
                finalBgBottom
            )

            // Draw background with slightly different colors - Added 'else' branch
            textBgPaint.color = when (detection.type) {
                FireDetectionType.FIRE -> "#A0DD0000".toColorInt() // Semi-transparent Red BG
                FireDetectionType.SMOKE -> "#A000BCD4".toColorInt() // Semi-transparent Cyan BG
                // else -> "#A0888888".toColorInt() // Default background or handle error
            }
            canvas.drawRect(textBg, textBgPaint)

            // Draw the text label
            canvas.drawText(
                label,
                rect.left + textPadding,
                textY,
                textPaint
            )
        }
    }
}