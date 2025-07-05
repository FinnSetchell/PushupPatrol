package com.example.pushuppatrol.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.max
import kotlin.math.min

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentPose: Pose? = null
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var scaleFactorX: Float = 1.0f
    private var scaleFactorY: Float = 1.0f
    private var postScaleWidthOffset: Float = 0f
    private var postScaleHeightOffset: Float = 0f
    private var isFrontCamera: Boolean = true

    // Detection Debug Parameters
    var minObservedY: Float? = null
    var maxObservedY: Float? = null
    var downThresholdY: Float? = null // Y-coordinate for "go down to here"
    var upThresholdY: Float? = null   // Y-coordinate for "come up to here"
    var currentReportedY: Float? = null // The Y value currently being used for detection (e.g., nose.y)

    private val pointPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        strokeWidth = 12.0f
    }

    private val linePaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
    }

    // Paints for Debug Visualizations
    private val minMaxPaint = Paint().apply {
        color = Color.GREEN // For min/max observed Y lines
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        alpha = 150 // Slightly transparent
    }

    private val thresholdPaint = Paint().apply {
        color = Color.YELLOW // For up/down threshold lines
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f) // Dashed line
    }

    private val currentYPaint = Paint().apply {
        color = Color.RED // For current Y indicator
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
        alpha = 200
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f // Adjust as needed
        setShadowLayer(5.0f, 0f, 0f, Color.BLACK) // Text shadow for better visibility
    }


    private val landmarkPairs = listOf(
        // ... (your existing landmarkPairs)
        Pair(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
        Pair(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW),
        Pair(PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST),
        Pair(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW),
        Pair(PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST),
        Pair(PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP),
        Pair(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP),
        Pair(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
        Pair(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE),
        Pair(PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
        Pair(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE),
        Pair(PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE),
        Pair(PoseLandmark.NOSE, PoseLandmark.LEFT_EYE_INNER),
        Pair(PoseLandmark.LEFT_EYE_INNER, PoseLandmark.LEFT_EYE),
        Pair(PoseLandmark.LEFT_EYE, PoseLandmark.LEFT_EYE_OUTER),
        Pair(PoseLandmark.LEFT_EYE_OUTER, PoseLandmark.LEFT_EAR),
        Pair(PoseLandmark.NOSE, PoseLandmark.RIGHT_EYE_INNER),
        Pair(PoseLandmark.RIGHT_EYE_INNER, PoseLandmark.RIGHT_EYE),
        Pair(PoseLandmark.RIGHT_EYE, PoseLandmark.RIGHT_EYE_OUTER),
        Pair(PoseLandmark.RIGHT_EYE_OUTER, PoseLandmark.RIGHT_EAR),
        Pair(PoseLandmark.LEFT_MOUTH, PoseLandmark.RIGHT_MOUTH)
    )

    fun updatePose(
        pose: Pose?,
        imageWidth: Int,
        imageHeight: Int,
        isFront: Boolean,
        debugMinY: Float? = null,
        debugMaxY: Float? = null,
        debugDownThresholdY: Float? = null,
        debugUpThresholdY: Float? = null,
        debugCurrentY: Float? = null
    ) {
        this.currentPose = pose
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.isFrontCamera = isFront

        this.minObservedY = debugMinY
        this.maxObservedY = debugMaxY
        this.downThresholdY = debugDownThresholdY
        this.upThresholdY = debugUpThresholdY
        this.currentReportedY = debugCurrentY

        invalidate()
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calculate scaling (same as before)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (imageWidth == 0 || imageHeight == 0) return // Avoid division by zero if not initialized

        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val viewAspectRatio = viewWidth / viewHeight

        if (imageAspectRatio > viewAspectRatio) {
            scaleFactorX = viewWidth / imageWidth.toFloat()
            scaleFactorY = viewWidth / imageAspectRatio / imageHeight.toFloat()
            postScaleWidthOffset = 0f
            postScaleHeightOffset = (viewHeight - scaleFactorY * imageHeight.toFloat()) / 2
        } else {
            scaleFactorX = viewHeight * imageAspectRatio / imageWidth.toFloat()
            scaleFactorY = viewHeight / imageHeight.toFloat()
            postScaleWidthOffset = (viewWidth - scaleFactorX * imageWidth.toFloat()) / 2
            postScaleHeightOffset = 0f
        }

        // Draw Pose (same as before)
        currentPose?.let { pose ->
            for (landmark in pose.allPoseLandmarks) {
                if (landmark.inFrameLikelihood > 0.3f) {
                    val translatedX = translateX(landmark.position.x)
                    val translatedY = translateY(landmark.position.y)
                    canvas.drawCircle(translatedX, translatedY, pointPaint.strokeWidth / 2, pointPaint)
                }
            }
            for (pair in landmarkPairs) {
                val startLandmark = pose.getPoseLandmark(pair.first)
                val endLandmark = pose.getPoseLandmark(pair.second)
                if (startLandmark != null && endLandmark != null &&
                    startLandmark.inFrameLikelihood > 0.3f && endLandmark.inFrameLikelihood > 0.3f
                ) {
                    canvas.drawLine(
                        translateX(startLandmark.position.x),
                        translateY(startLandmark.position.y),
                        translateX(endLandmark.position.x),
                        translateY(endLandmark.position.y),
                        linePaint
                    )
                }
            }
        }

        // --- Draw Debug Visualizations ---
        val lineStartX = viewWidth * 0.1f // Draw lines starting from 10% of view width
        val lineEndX = viewWidth * 0.9f   // Draw lines ending at 90% of view width

        // Draw Min Observed Y line
        minObservedY?.let {
            val yPos = translateY(it)
            canvas.drawLine(lineStartX, yPos, lineEndX, yPos, minMaxPaint)
            canvas.drawText("MinY", lineEndX + 10, yPos + textPaint.textSize / 3, textPaint)
        }

        // Draw Max Observed Y line
        maxObservedY?.let {
            val yPos = translateY(it)
            canvas.drawLine(lineStartX, yPos, lineEndX, yPos, minMaxPaint)
            canvas.drawText("MaxY", lineEndX + 10, yPos + textPaint.textSize / 3, textPaint)
        }

        // Draw Down Threshold Y line
        downThresholdY?.let {
            val yPos = translateY(it)
            canvas.drawLine(lineStartX, yPos, lineEndX, yPos, thresholdPaint)
            canvas.drawText("DownThr", lineEndX + 10, yPos + textPaint.textSize / 3, textPaint)
        }

        // Draw Up Threshold Y line
        upThresholdY?.let {
            val yPos = translateY(it)
            canvas.drawLine(lineStartX, yPos, lineEndX, yPos, thresholdPaint)
            canvas.drawText("UpThr", lineEndX + 10, yPos + textPaint.textSize / 3, textPaint)
        }

        // Draw Current Reported Y indicator line (e.g., nose Y)
        currentReportedY?.let {
            val yPos = translateY(it)
            // Draw a shorter, distinct line for current Y
            val currentLineStartX = viewWidth * 0.2f
            val currentLineEndX = viewWidth * 0.8f
            canvas.drawLine(currentLineStartX, yPos, currentLineEndX, yPos, currentYPaint)
            canvas.drawText("CurrentY", currentLineEndX + 10, yPos + textPaint.textSize / 3, textPaint)
        }
    }

    private fun translateX(x: Float): Float {
        return if (isFrontCamera) {
            (imageWidth - x) * scaleFactorX + postScaleWidthOffset
        } else {
            x * scaleFactorX + postScaleWidthOffset
        }
    }

    private fun translateY(y: Float): Float {
        // This is where you might need to adjust if Y-axis is inverted in your calculation
        // The current drawing assumes that a higher Y value from ML Kit means lower on the screen.
        return y * scaleFactorY + postScaleHeightOffset
    }

    fun clear() {
        currentPose = null
        minObservedY = null
        maxObservedY = null
        downThresholdY = null
        upThresholdY = null
        currentReportedY = null
        invalidate()
    }
}
