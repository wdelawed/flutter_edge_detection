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
private const val MIN_AREA_RATIO = 0.20
private const val MAX_AREA_RATIO = 0.70
private const val BORDER_MARGIN_PX = 12.0

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

fun processPicture(frame: Mat, requireTemporalStability: Boolean = false): Corners? {

    // ---------- 1. Resize ----------
    val scale = TARGET_WIDTH / frame.width()
    val invScale = frame.width() / TARGET_WIDTH
    val resized = Mat()
    Imgproc.resize(
        frame,
        resized,
        Size(TARGET_WIDTH, frame.height() * scale)
    )
    val frameArea = resized.width().toDouble() * resized.height().toDouble()

    // ---------- 2. Gray + normalize ----------
    val gray = Mat()
    Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    clahe.apply(gray, gray)
    Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

    // ---------- 3. Build two candidate masks ----------
    val edges = Mat()
    Imgproc.Canny(gray, edges, 35.0, 120.0)

    val bin = Mat()
    Imgproc.adaptiveThreshold(
        gray,
        bin,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY,
        31,
        8.0
    )
    Core.bitwise_not(bin, bin)

    val merged = Mat()
    Core.bitwise_or(edges, bin, merged)

    val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
    Imgproc.morphologyEx(merged, merged, Imgproc.MORPH_CLOSE, closeKernel)
    Imgproc.dilate(
        merged,
        merged,
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    )

    // ---------- 4. Find and score candidates ----------
    val contours = ArrayList<MatOfPoint>()
    Imgproc.findContours(merged, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

    var best: List<Point>? = null
    var bestScore = -1.0

    for (c in contours) {
        val cArea = Imgproc.contourArea(c)
        if (cArea < frameArea * MIN_AREA_RATIO || cArea > frameArea * MAX_AREA_RATIO) continue

        val c2f = MatOfPoint2f(*c.toArray())
        val peri = Imgproc.arcLength(c2f, true)
        if (peri <= 0.0) continue

        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)

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
            it.x <= BORDER_MARGIN_PX ||
            it.y <= BORDER_MARGIN_PX ||
            it.x >= resized.width() - BORDER_MARGIN_PX ||
            it.y >= resized.height() - BORDER_MARGIN_PX
        }
        if (touchesBorder) continue

        val qArea = quadArea(sorted)
        if (qArea <= 0.0) continue

        val score = qArea

        if (score > bestScore) {
            bestScore = score
            best = sorted
        }
    }

    gray.release()
    edges.release()
    bin.release()
    merged.release()
    closeKernel.release()
    clahe.collectGarbage()

    if (best != null) {
        return if (requireTemporalStability) {
            // Preview overlay expects points in the same resized coordinate space.
            Corners(best!!, resized.size())
        } else {
            // Capture/crop expects points in original image space.
            val mapped = best!!.map { Point(it.x * invScale, it.y * invScale) }
            Corners(mapped, frame.size())
        }
    }

    // ---------- 5. Nothing valid ----------
    stableCount = 0
    lastAccepted = null
    return null
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
