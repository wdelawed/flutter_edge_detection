package com.jayfinava.flutteredgedetection.processor

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

const val TAG: String = "PassportProcessor2"


private var lastAccepted: List<Point>? = null
private var stableCount = 0

private const val TARGET_WIDTH = 720.0
private const val FAST_TARGET_WIDTH = 560.0
private const val MIN_AREA_RATIO = 0.20
private const val MAX_AREA_RATIO = 0.70
private const val AUTO_MIN_AREA_RATIO = 0.10
private const val AUTO_MAX_AREA_RATIO = 0.88
private const val BORDER_MARGIN_PX = 12.0
private const val AUTO_BORDER_MARGIN_PX = 4.0

// ----- Public API -----


private fun distance(a: Point, b: Point): Double =
    hypot(a.x - b.x, a.y - b.y)

private fun quadArea(points: List<Point>): Double {
    if (points.size != 4) return 0.0
    var sum = 0.0
    for (i in points.indices) {
        val j = (i + 1) % points.size
        sum += points[i].x * points[j].y - points[j].x * points[i].y
    }
    return abs(sum) * 0.5
}

fun processPicture(
    frame: Mat,
    requireTemporalStability: Boolean = false,
    fastMode: Boolean = false,
    relaxedValidation: Boolean = false
): Corners? {
    if (frame.empty() || frame.width() == 0 || frame.height() == 0) return null

    val useFastProfile = fastMode
    val targetWidth = if (useFastProfile) {
        min(FAST_TARGET_WIDTH, frame.width().toDouble())
    } else {
        TARGET_WIDTH
    }
    val minAreaRatio = if (relaxedValidation) AUTO_MIN_AREA_RATIO else MIN_AREA_RATIO
    val maxAreaRatio = if (relaxedValidation) AUTO_MAX_AREA_RATIO else MAX_AREA_RATIO
    val borderMarginPx = if (relaxedValidation) AUTO_BORDER_MARGIN_PX else BORDER_MARGIN_PX
    val cannyLow = if (useFastProfile) 25.0 else 35.0
    val cannyHigh = if (useFastProfile) 90.0 else 120.0
    val adaptiveBlockSize = if (useFastProfile) 23 else 31
    val adaptiveC = if (useFastProfile) 6.0 else 8.0
    val closeKernelSize = if (useFastProfile) 5.0 else 7.0
    val dilateKernelSize = if (useFastProfile) 2.0 else 3.0
    val contourMode = if (useFastProfile) Imgproc.RETR_EXTERNAL else Imgproc.RETR_LIST
    val approxRatio = if (useFastProfile) 0.028 else 0.02

    // ---------- 1. Resize ----------
    val scale = targetWidth / frame.width()
    val invScale = frame.width() / targetWidth
    val resized = Mat()
    Imgproc.resize(
        frame,
        resized,
        Size(targetWidth, frame.height() * scale)
    )
    val frameArea = resized.width().toDouble() * resized.height().toDouble()
    val resizedSize = resized.size()

    // ---------- 2. Gray + normalize ----------
    val gray = Mat()
    when (resized.channels()) {
        1 -> resized.copyTo(gray)
        4 -> Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
        else -> Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)
    }
    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    clahe.apply(gray, gray)
    Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

    // ---------- 3. Build two candidate masks ----------
    val edges = Mat()
    Imgproc.Canny(gray, edges, cannyLow, cannyHigh)

    val bin = Mat()
    Imgproc.adaptiveThreshold(
        gray,
        bin,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY,
        adaptiveBlockSize,
        adaptiveC
    )
    Core.bitwise_not(bin, bin)

    val merged = Mat()
    Core.bitwise_or(edges, bin, merged)

    val closeKernel =
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(closeKernelSize, closeKernelSize))
    val dilateKernel =
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(dilateKernelSize, dilateKernelSize))
    Imgproc.morphologyEx(merged, merged, Imgproc.MORPH_CLOSE, closeKernel)
    Imgproc.dilate(
        merged,
        merged,
        dilateKernel
    )

    // ---------- 4. Find and score candidates ----------
    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(merged, contours, hierarchy, contourMode, Imgproc.CHAIN_APPROX_SIMPLE)

    var best: List<Point>? = null
    var bestScore = -1.0

    for (c in contours) {
        var c2f: MatOfPoint2f? = null
        var approx: MatOfPoint2f? = null
        try {
            val cArea = Imgproc.contourArea(c)
            if (cArea < frameArea * minAreaRatio || cArea > frameArea * maxAreaRatio) continue

            c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            if (peri <= 0.0) continue

            approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, approxRatio * peri, true)

            val points = if (approx.total() == 4L) {
                approx.toArray().toList()
            } else {
                val rect = Imgproc.minAreaRect(c2f)
                val rectPts = arrayOfNulls<Point>(4)
                rect.points(rectPts)
                rectPts.filterNotNull()
            }
            if (points.size != 4) continue

            val sorted = sortPoints(points)
            val touchesBorder = sorted.any {
                it.x <= borderMarginPx ||
                it.y <= borderMarginPx ||
                it.x >= resized.width() - borderMarginPx ||
                it.y >= resized.height() - borderMarginPx
            }
            if (touchesBorder) continue

            val qArea = quadArea(sorted)
            if (qArea <= 0.0) continue

            val score = qArea

            if (score > bestScore) {
                bestScore = score
                best = sorted
            }
        } finally {
            c2f?.release()
            approx?.release()
            c.release()
        }
    }

    gray.release()
    edges.release()
    bin.release()
    merged.release()
    closeKernel.release()
    dilateKernel.release()
    hierarchy.release()
    clahe.collectGarbage()

    val result = if (best != null) {
        if (requireTemporalStability) {
            // Preview overlay expects points in the same resized coordinate space.
            Corners(best!!, resizedSize)
        } else {
            // Capture/crop expects points in original image space.
            val mapped = best!!.map { Point(it.x * invScale, it.y * invScale) }
            Corners(mapped, frame.size())
        }
    } else {
        null
    }
    resized.release()

    if (result == null) {
        // ---------- 5. Nothing valid ----------
        stableCount = 0
        lastAccepted = null
    }
    return result
}

fun cropPicture(picture: Mat, pts: List<Point>): Mat {
    pts.forEach { Log.i(TAG, "point: $it") }

    val tl = pts[0]
    val tr = pts[1]
    val br = pts[2]
    val bl = pts[3]

    val widthA = hypot(br.x - bl.x, br.y - bl.y)
    val widthB = hypot(tr.x - tl.x, tr.y - tl.y)
    val dw = max(widthA, widthB)
    val maxWidth = dw.toInt()

    val heightA = hypot(tr.x - br.x, tr.y - br.y)
    val heightB = hypot(tl.x - bl.x, tl.y - bl.y)
    val dh = max(heightA, heightB)
    val maxHeight = dh.toInt()

    val croppedPic = Mat(maxHeight, maxWidth, CvType.CV_8UC4)
    val srcMat = MatOfPoint2f(tl, tr, br, bl)
    val dstMat = MatOfPoint2f(
        Point(0.0, 0.0),
        Point(dw, 0.0),
        Point(dw, dh),
        Point(0.0, dh)
    )

    val m = Imgproc.getPerspectiveTransform(srcMat, dstMat)
    Imgproc.warpPerspective(picture, croppedPic, m, croppedPic.size())

    m.release()
    srcMat.release()
    dstMat.release()
    Log.i(TAG, "crop finish")
    return croppedPic
}

fun enhancePicture(src: Bitmap?): Bitmap {
    val srcMat = Mat()
    Utils.bitmapToMat(src, srcMat)
    Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2GRAY)
    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    clahe.apply(srcMat, srcMat)
    Imgproc.adaptiveThreshold(
        srcMat,
        srcMat,
        255.0,
        Imgproc.ADAPTIVE_THRESH_MEAN_C,
        Imgproc.THRESH_BINARY,
        15,
        15.0
    )
    val result = Bitmap.createBitmap(src?.width ?: 1080, src?.height ?: 1920, Bitmap.Config.RGB_565)
    Utils.matToBitmap(srcMat, result, true)
    srcMat.release()
    return result
}

private fun sortPoints(points: List<Point>): List<Point> {
    val tl = points.minByOrNull { it.x + it.y } ?: Point()
    val br = points.maxByOrNull { it.x + it.y } ?: Point()
    val tr = points.minByOrNull { it.y - it.x } ?: Point()
    val bl = points.maxByOrNull { it.y - it.x } ?: Point()
    return listOf(tl, tr, br, bl)
}
