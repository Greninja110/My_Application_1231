package com.example.arcorestream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.google.ar.core.TrackingState
import timber.log.Timber

class StatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Tracking state for visualization
    var trackingState: TrackingState? = null
        set(value) {
            field = value
            invalidate()  // Redraw when tracking state changes
        }

    // FPS counter
    var fps: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    // Connection status
    var isConnected: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Paint objects
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
    }

    private val boxPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textBounds = Rect()

    class StatusView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {

        // Tracking state for visualization
        var trackingState: TrackingState? = null
            set(value) {
                field = value
                invalidate()  // Redraw when tracking state changes
            }

        // FPS counter
        var fps: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        // Connection status
        var isConnected: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        // Paint objects
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            isAntiAlias = true
        }

        private val boxPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val textBounds = Rect()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw tracking state
            drawTrackingState(canvas)

            // Draw FPS counter
            drawFPS(canvas)

            // Draw connection status
            drawConnectionStatus(canvas)
        }

        private fun drawTrackingState(canvas: Canvas) {
            val text = "Tracking: ${trackingState?.name ?: "UNKNOWN"}"
            val color = when (trackingState) {
                TrackingState.TRACKING -> Color.GREEN
                TrackingState.PAUSED -> Color.YELLOW
                TrackingState.STOPPED -> Color.RED
                else -> Color.GRAY
            }

            textPaint.color = Color.BLACK
            textPaint.style = Paint.Style.FILL_AND_STROKE
            textPaint.strokeWidth = 4f
            textPaint.getTextBounds(text, 0, text.length, textBounds)

            val x = 20f
            val y = textBounds.height() + 20f

            canvas.drawText(text, x, y, textPaint)

            textPaint.color = color
            textPaint.style = Paint.Style.FILL
            textPaint.strokeWidth = 0f
            canvas.drawText(text, x, y, textPaint)
        }

        private fun drawFPS(canvas: Canvas) {
            val text = "FPS: %.1f".format(fps)

            textPaint.color = Color.BLACK
            textPaint.style = Paint.Style.FILL_AND_STROKE
            textPaint.strokeWidth = 4f
            textPaint.getTextBounds(text, 0, text.length, textBounds)

            val x = 20f
            val y = textBounds.height() * 2 + 40f

            canvas.drawText(text, x, y, textPaint)

            textPaint.color = Color.WHITE
            textPaint.style = Paint.Style.FILL
            textPaint.strokeWidth = 0f
            canvas.drawText(text, x, y, textPaint)
        }

        private fun drawConnectionStatus(canvas: Canvas) {
            val text = if (isConnected) "Connected" else "Disconnected"
            val color = if (isConnected) Color.GREEN else Color.RED

            textPaint.color = Color.BLACK
            textPaint.style = Paint.Style.FILL_AND_STROKE
            textPaint.strokeWidth = 4f
            textPaint.getTextBounds(text, 0, text.length, textBounds)

            val x = 20f
            val y = textBounds.height() * 3 + 60f

            canvas.drawText(text, x, y, textPaint)

            textPaint.color = color
            textPaint.style = Paint.Style.FILL
            textPaint.strokeWidth = 0f
            canvas.drawText(text, x, y, textPaint)
        }
    }
}