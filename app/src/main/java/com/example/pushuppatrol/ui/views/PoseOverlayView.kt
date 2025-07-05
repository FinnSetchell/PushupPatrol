package com.example.pushuppatrol.ui.views // Or a suitable package

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
    private var isFrontCamera: Boolean = true // Default to true, update if using back camera

    private val pointPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        strokeWidth = 12.0f // Size of the landmark points
    }

    private val linePaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.STROKE
        strokeWidth = 6.0f // Thickness of the connecting lines
    }

    private val landmarkPairs = listOf(
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
        // Head
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

    fun updatePose(pose: Pose?, imageWidth: Int, imageHeight: Int, isFront: Boolean) {
        this.currentPose = pose
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.isFrontCamera = isFront

        // Call invalidate to trigger a redraw
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        currentPose ?: return // If no pose, nothing to draw

        // Calculate scaling and offset factors
        // This logic assumes the PreviewView might be scaled to fit or fill the view bounds.
        // It tries to map the image coordinates (from ML Kit) to the view coordinates.
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val viewAspectRatio = viewWidth / viewHeight

        if (imageAspectRatio > viewAspectRatio) { // Image is wider than view (letterboxing)
            scaleFactorX = viewWidth / imageWidth.toFloat()
            scaleFactorY = viewWidth / imageAspectRatio / imageHeight.toFloat() // Maintain aspect ratio
            postScaleWidthOffset = 0f
            postScaleHeightOffset = (viewHeight - scaleFactorY * imageHeight.toFloat()) / 2
        } else { // Image is taller than view (pillarboxing)
            scaleFactorX = viewHeight * imageAspectRatio / imageWidth.toFloat() // Maintain aspect ratio
            scaleFactorY = viewHeight / imageHeight.toFloat()
            postScaleWidthOffset = (viewWidth - scaleFactorX * imageWidth.toFloat()) / 2
            postScaleHeightOffset = 0f
        }


        // Draw all landmarks
        for (landmark in currentPose!!.allPoseLandmarks) {
            if (landmark.inFrameLikelihood > 0.3f) { // Only draw if reasonably in frame
                val translatedX = translateX(landmark.position.x)
                val translatedY = translateY(landmark.position.y)
                canvas.drawCircle(translatedX, translatedY, pointPaint.strokeWidth / 2, pointPaint)
            }
        }

        // Draw lines connecting landmark pairs
        for (pair in landmarkPairs) {
            val startLandmark = currentPose!!.getPoseLandmark(pair.first)
            val endLandmark = currentPose!!.getPoseLandmark(pair.second)

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

    private fun translateX(x: Float): Float {
        return if (isFrontCamera) {
            // Mirror X coordinates for front camera
            (imageWidth - x) * scaleFactorX + postScaleWidthOffset
        } else {
            x * scaleFactorX + postScaleWidthOffset
        }
    }

    private fun translateY(y: Float): Float {
        return y * scaleFactorY + postScaleHeightOffset
    }

    fun clear() {
        currentPose = null
        invalidate()
    }
}
