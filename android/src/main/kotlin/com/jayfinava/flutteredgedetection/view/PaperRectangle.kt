package com.jayfinava.flutteredgedetection.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.jayfinava.flutteredgedetection.processor.Corners
import com.jayfinava.flutteredgedetection.processor.TAG
import org.opencv.core.Point
import org.opencv.core.Size
import kotlin.math.abs


class PaperRectangle : View {
    constructor(context: Context) : super(context)
    constructor(context: Context, attributes: AttributeSet) : super(context, attributes)
    constructor(context: Context, attributes: AttributeSet, defTheme: Int) : super(
        context,
        attributes,
        defTheme
    )

    private val rectPaint = Paint()
    private val circlePaint = Paint()
    private val autoGuidePaint = Paint()
    private val autoGuideDimPaint = Paint()
    private var ratioX: Double = 1.0
    private var ratioY: Double = 1.0
    private var tl: Point = Point()
    private var tr: Point = Point()
    private var br: Point = Point()
    private var bl: Point = Point()
    private val path: Path = Path()
    private var point2Move = Point()
    private var cropMode = false
    private var autoGuideMode = false
    private var autoGuideDetected = false
    private val autoGuideZone = RectF()
    private var latestDownX = 0.0F
    private var latestDownY = 0.0F

    init {
        rectPaint.color = Color.argb(128, 255, 255, 255)
        rectPaint.isAntiAlias = true
        rectPaint.isDither = true
        rectPaint.strokeWidth = 6F
        rectPaint.style = Paint.Style.FILL_AND_STROKE
        rectPaint.strokeJoin = Paint.Join.ROUND    // set the join to round you want
        rectPaint.strokeCap = Paint.Cap.ROUND      // set the paint cap to round too
        rectPaint.pathEffect = CornerPathEffect(10f)

        circlePaint.color = Color.WHITE
        circlePaint.isDither = true
        circlePaint.isAntiAlias = true
        circlePaint.strokeWidth = 4F
        circlePaint.style = Paint.Style.STROKE

        autoGuidePaint.isAntiAlias = true
        autoGuidePaint.isDither = true
        autoGuidePaint.style = Paint.Style.STROKE
        autoGuidePaint.strokeWidth = 8f

        autoGuideDimPaint.color = Color.argb(110, 0, 0, 0)
        autoGuideDimPaint.style = Paint.Style.FILL
    }

    fun setAutoGuideMode(enabled: Boolean) {
        autoGuideMode = enabled
        autoGuideDetected = false
        if (enabled) {
            path.reset()
            cropMode = false
        }
        invalidate()
    }

    fun setAutoGuideDetected(detected: Boolean) {
        if (autoGuideDetected == detected) return
        autoGuideDetected = detected
        invalidate()
    }

    fun isInsideAutoGuide(corners: Corners): Boolean {
        if (!autoGuideMode || autoGuideZone.isEmpty || measuredWidth == 0 || measuredHeight == 0) {
            return false
        }

        ratioX = corners.size.width.div(measuredWidth)
        ratioY = corners.size.height.div(measuredHeight)
        if (ratioX == 0.0 || ratioY == 0.0) return false

        val zone = RectF(autoGuideZone)
        val mappedPoints = corners.corners.map { point ->
            PointF((point.x / ratioX).toFloat(), (point.y / ratioY).toFloat())
        }
        if (mappedPoints.size != 4) return false

        val bounds = RectF(
            mappedPoints.minOf { it.x },
            mappedPoints.minOf { it.y },
            mappedPoints.maxOf { it.x },
            mappedPoints.maxOf { it.y }
        )
        if (bounds.width() < bounds.height() * 1.1f) return false
        val boundsArea = bounds.width() * bounds.height()
        if (boundsArea <= 0f) return false

        val center = PointF(
            mappedPoints.map { it.x }.average().toFloat(),
            mappedPoints.map { it.y }.average().toFloat()
        )
        if (!zone.contains(center.x, center.y)) return false

        val intersection = RectF()
        if (!intersection.setIntersect(zone, bounds)) return false
        val overlapRatio = (intersection.width() * intersection.height()) / boundsArea

        return overlapRatio >= 0.55f
    }

    fun onCornersDetected(corners: Corners) {

        ratioX = corners.size.width.div(measuredWidth)
        ratioY = corners.size.height.div(measuredHeight)

        for (i in 0..3) {
            for (j in i + 1..3) {
                if (corners.corners[i]?.equals(corners.corners[j]) == true) {
                    resize()
                    path.reset()
                    path.close()
                    invalidate()
                    return
                }
            }
        }

        tl = corners.corners[0] ?: Point()
        tr = corners.corners[1] ?: Point()
        br = corners.corners[2] ?: Point()
        bl = corners.corners[3] ?: Point()

        Log.i(TAG, "POINTS tl ------>  $tl corners")
        Log.i(TAG, "POINTS tr ------>  $tr corners")
        Log.i(TAG, "POINTS br ------>  $br corners")
        Log.i(TAG, "POINTS bl ------>  $bl corners")

        resize()
        path.reset()
        path.moveTo(tl.x.toFloat(), tl.y.toFloat())
        path.lineTo(tr.x.toFloat(), tr.y.toFloat())
        path.lineTo(br.x.toFloat(), br.y.toFloat())
        path.lineTo(bl.x.toFloat(), bl.y.toFloat())
        path.close()
        invalidate()
    }

    fun onCornersNotDetected() {
        path.reset()
        invalidate()
    }

    fun onCorners2Crop(corners: Corners?, size: Size?, paperWidth: Int, paperHeight: Int) {
        if (size == null) {
            return
        }

        cropMode = true
        tl = corners?.corners?.get(0) ?: Point(size.width * 0.1, size.height * 0.1)
        tr = corners?.corners?.get(1) ?: Point(size.width * 0.9, size.height * 0.1)
        br = corners?.corners?.get(2) ?: Point(size.width * 0.9, size.height * 0.9)
        bl = corners?.corners?.get(3) ?: Point(size.width * 0.1, size.height * 0.9)
        ratioX = size.width?.div(paperWidth) ?: 1.0
        ratioY = size.height?.div(paperHeight) ?: 1.0
        resize()
        movePoints()
    }

    fun getCorners2Crop(): List<Point> {
        reverseSize()
        return listOf(tl, tr, br, bl)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (autoGuideMode) {
            drawAutoGuide(canvas)
            return
        }

        rectPaint.color = Color.WHITE
        rectPaint.strokeWidth = 6F
        rectPaint.style = Paint.Style.STROKE
        canvas.drawPath(path, rectPaint)

        rectPaint.color = Color.argb(128, 255, 255, 255)
        rectPaint.strokeWidth = 0F
        rectPaint.style = Paint.Style.FILL
        canvas.drawPath(path, rectPaint)

        if (cropMode) {
            canvas.drawCircle(tl.x.toFloat(), tl.y.toFloat(), 20F, circlePaint)
            canvas.drawCircle(tr.x.toFloat(), tr.y.toFloat(), 20F, circlePaint)
            canvas.drawCircle(bl.x.toFloat(), bl.y.toFloat(), 20F, circlePaint)
            canvas.drawCircle(br.x.toFloat(), br.y.toFloat(), 20F, circlePaint)
        }
    }

    private fun drawAutoGuide(canvas: Canvas) {
        if (autoGuideZone.isEmpty) return

        val outer = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRoundRect(autoGuideZone, 22f, 22f, Path.Direction.CCW)
        }
        canvas.drawPath(outer, autoGuideDimPaint)

        autoGuidePaint.color = if (autoGuideDetected) {
            Color.parseColor("#2ECC71")
        } else {
            Color.parseColor("#FF4D4F")
        }
        canvas.drawRoundRect(autoGuideZone, 22f, 22f, autoGuidePaint)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {

        if (!cropMode) {
            return false
        }
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                latestDownX = event.x
                latestDownY = event.y
                calculatePoint2Move(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                point2Move.x = (event.x - latestDownX) + point2Move.x
                point2Move.y = (event.y - latestDownY) + point2Move.y
                movePoints()
                latestDownY = event.y
                latestDownX = event.x
            }
        }
        return true
    }

    private fun calculatePoint2Move(downX: Float, downY: Float) {
        val points = listOf(tl, tr, br, bl)
        point2Move = points.minByOrNull { abs((it.x - downX).times(it.y - downY)) }
            ?: tl
    }

    private fun movePoints() {
        path.reset()
        path.moveTo(tl.x.toFloat(), tl.y.toFloat())
        path.lineTo(tr.x.toFloat(), tr.y.toFloat())
        path.lineTo(br.x.toFloat(), br.y.toFloat())
        path.lineTo(bl.x.toFloat(), bl.y.toFloat())
        path.close()
        invalidate()
    }


    private fun resize() {
        tl.x = tl.x.div(ratioX)
        tl.y = tl.y.div(ratioY)
        tr.x = tr.x.div(ratioX)
        tr.y = tr.y.div(ratioY)
        br.x = br.x.div(ratioX)
        br.y = br.y.div(ratioY)
        bl.x = bl.x.div(ratioX)
        bl.y = bl.y.div(ratioY)
    }

    private fun reverseSize() {
        tl.x = tl.x.times(ratioX)
        tl.y = tl.y.times(ratioY)
        tr.x = tr.x.times(ratioX)
        tr.y = tr.y.times(ratioY)
        br.x = br.x.times(ratioX)
        br.y = br.y.times(ratioY)
        bl.x = bl.x.times(ratioX)
        bl.y = bl.y.times(ratioY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val passportRatio = 125.0 / 88.0
        val maxZoneWidth = w * 0.92
        val maxZoneHeight = h * 0.62

        var zoneWidth = maxZoneWidth
        var zoneHeight = zoneWidth / passportRatio
        if (zoneHeight > maxZoneHeight) {
            zoneHeight = maxZoneHeight
            zoneWidth = zoneHeight * passportRatio
        }

        val centerX = w * 0.5
        val centerY = h * 0.42
        autoGuideZone.set(
            (centerX - zoneWidth * 0.5).toFloat(),
            (centerY - zoneHeight * 0.5).toFloat(),
            (centerX + zoneWidth * 0.5).toFloat(),
            (centerY + zoneHeight * 0.5).toFloat()
        )
    }
}
