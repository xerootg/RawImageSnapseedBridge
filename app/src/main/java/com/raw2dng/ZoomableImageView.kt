package com.raw2dng

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Custom ImageView with pinch-to-zoom and pan support.
 * Supports being used inside ViewPager2 for swipe navigation.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    private var minScale = 0.5f
    private var maxScale = 10f
    private var currentScale = 1f
    private var fitScale = 1f

    private var imageWidth = 0f
    private var imageHeight = 0f
    private var viewWidth = 0f
    private var viewHeight = 0f

    private var onZoomChangeListener: ((Float) -> Unit)? = null

    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    
    // For ViewPager2 integration
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = matrix
    }

    fun setOnZoomChangeListener(listener: (Float) -> Unit) {
        onZoomChangeListener = listener
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        bm?.let {
            imageWidth = it.width.toFloat()
            imageHeight = it.height.toFloat()
            fitToScreen()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w.toFloat()
        viewHeight = h.toFloat()
        if (imageWidth > 0 && imageHeight > 0) {
            fitToScreen()
        }
    }

    fun fitToScreen() {
        if (viewWidth == 0f || viewHeight == 0f || imageWidth == 0f || imageHeight == 0f) return

        matrix.reset()

        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        fitScale = min(scaleX, scaleY)
        currentScale = fitScale

        val dx = (viewWidth - imageWidth * fitScale) / 2
        val dy = (viewHeight - imageHeight * fitScale) / 2

        matrix.postScale(fitScale, fitScale)
        matrix.postTranslate(dx, dy)

        imageMatrix = matrix
        onZoomChangeListener?.invoke(currentScale)
    }

    fun zoomIn() {
        val newScale = min(currentScale * 1.5f, maxScale)
        zoomTo(newScale)
    }

    fun zoomOut() {
        val newScale = max(currentScale / 1.5f, minScale)
        zoomTo(newScale)
    }

    private fun zoomTo(targetScale: Float) {
        val scaleFactor = targetScale / currentScale
        val cx = viewWidth / 2
        val cy = viewHeight / 2

        matrix.postScale(scaleFactor, scaleFactor, cx, cy)
        currentScale = targetScale
        constrainMatrix()
        imageMatrix = matrix
        onZoomChangeListener?.invoke(currentScale)
    }

    fun getZoomLevel(): Float = currentScale

    fun resetZoom() {
        fitToScreen()
    }

    /**
     * Check if the image can scroll horizontally in the given direction.
     * @param direction Negative for left, positive for right
     * @return true if the image can scroll in that direction
     */
    private fun canScrollHorizontallyInternal(direction: Int): Boolean {
        if (imageWidth == 0f || viewWidth == 0f) return false
        
        val values = FloatArray(9)
        matrix.getValues(values)
        
        val transX = values[Matrix.MTRANS_X]
        val scaleX = values[Matrix.MSCALE_X]
        val scaledWidth = imageWidth * scaleX
        
        // If image is smaller than view, can't scroll
        if (scaledWidth <= viewWidth) return false
        
        return if (direction < 0) {
            // Scrolling left - check if there's content to the right
            transX + scaledWidth > viewWidth + 1
        } else {
            // Scrolling right - check if there's content to the left
            transX < -1
        }
    }

    /**
     * Check if we're at minimum (fit) scale
     */
    fun isAtMinScale(): Boolean {
        return currentScale <= fitScale * 1.05f // Small tolerance
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.x
                initialTouchY = event.y
                // Don't let parent intercept yet
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val dx = event.x - initialTouchX
                    val dy = event.y - initialTouchY
                    
                    // Check if this is a horizontal swipe
                    if (abs(dx) > abs(dy) && abs(dx) > touchSlop) {
                        // If at minimum scale, allow parent (ViewPager2) to handle swipe
                        if (isAtMinScale()) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                        } else {
                            // If zoomed, check if we're at the edge
                            val direction = if (dx > 0) 1 else -1
                            if (!canScrollHorizontallyInternal(direction)) {
                                // At edge, allow parent to handle
                                parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(matrix)
                    midPoint(mid, event)
                    mode = ZOOM
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG) {
                    matrix.set(savedMatrix)
                    val dx = event.x - start.x
                    val dy = event.y - start.y
                    matrix.postTranslate(dx, dy)
                    constrainMatrix()
                } else if (mode == ZOOM && event.pointerCount >= 2) {
                    val newDist = spacing(event)
                    if (newDist > 10f) {
                        matrix.set(savedMatrix)
                        var scale = newDist / oldDist
                        val newScale = currentScale * scale

                        // Limit scale
                        scale = when {
                            newScale > maxScale -> maxScale / currentScale
                            newScale < minScale -> minScale / currentScale
                            else -> scale
                        }

                        matrix.postScale(scale, scale, mid.x, mid.y)
                        currentScale = currentScale * scale
                        constrainMatrix()
                        onZoomChangeListener?.invoke(currentScale)
                    }
                }
            }
        }

        imageMatrix = matrix
        return true
    }

    private fun constrainMatrix() {
        val values = FloatArray(9)
        matrix.getValues(values)

        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        val scaleX = values[Matrix.MSCALE_X]

        val scaledWidth = imageWidth * scaleX
        val scaledHeight = imageHeight * scaleX

        var dx = 0f
        var dy = 0f

        // Constrain X
        if (scaledWidth <= viewWidth) {
            dx = (viewWidth - scaledWidth) / 2 - transX
        } else {
            if (transX > 0) dx = -transX
            else if (transX + scaledWidth < viewWidth) dx = viewWidth - (transX + scaledWidth)
        }

        // Constrain Y
        if (scaledHeight <= viewHeight) {
            dy = (viewHeight - scaledHeight) / 2 - transY
        } else {
            if (transY > 0) dy = -transY
            else if (transY + scaledHeight < viewHeight) dy = viewHeight - (transY + scaledHeight)
        }

        matrix.postTranslate(dx, dy)
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double-tap to toggle between fit and 100%
            if (currentScale > fitScale * 1.5f) {
                fitToScreen()
            } else {
                zoomTo(min(1f, maxScale))
            }
            return true
        }
    }
}
