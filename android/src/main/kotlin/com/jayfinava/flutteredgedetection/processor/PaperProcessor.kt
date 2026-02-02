package com.jayfinava.flutteredgedetection.processor

import android.os.SystemClock
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

const val TAG: String = "PassportProcessor2"


private var lastAccepted: List<Point>? = null
private var lastAcceptedAtMs: Long = 0L

private const val TARGET_WIDTH = 720.0
private const val MIN_AREA_RATIO = 0.20
private const val MAX_AREA_RATIO = 0.70
private const val BORDER_MARGIN_PX = 12.0
private const val PREVIEW_KEEP_LAST_MS = 500L
private const val PREVIEW_MAX_AVG_MOVE_PX = 20.0
private const val PREVIEW_MAX_CENTER_MOVE_PX = 20.0
private const val PREVIEW_MIN_AREA_SCALE = 0.90
private const val PREVIEW_MAX_AREA_SCALE = 1.10
private const val PREVIEW_SMOOTH_ALPHA = 0.24

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

private fun averageMovement(old: List<Point>, new: List<Point>): Double =
    old.zip(new).map { distance(it.first, it.second) }.average()

private fun quadCenter(points: List<Point>): Point {
    if (points.isEmpty()) return Point()
    var x = 0.0
    var y = 0.0
    points.forEach {
        x += it.x
        y += it.y
    }
    x /= points.size
    y /= points.size
    return Point(x, y)
}

private fun ccw(a: Point, b: Point, c: Point): Double =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

private fun segmentsIntersect(a: Point, b: Point, c: Point, d: Point): Boolean {
    val d1 = ccw(a, b, c)
    val d2 = ccw(a, b, d)
    val d3 = ccw(c, d, a)
    val d4 = ccw(c, d, b)
    return (d1 * d2 < 0.0) && (d3 * d4 < 0.0)
}

private fun isSelfIntersecting(pts: List<Point>): Boolean {
    if (pts.size != 4) return true
    return segmentsIntersect(pts[0], pts[1], pts[2], pts[3]) ||
        segmentsIntersect(pts[1], pts[2], pts[3], pts[0])
}

private fun normalizeQuadOrder(points: List<Point>): List<Point> {
    if (points.size != 4) return points
    val tlIndex = points.indices.minByOrNull { i -> points[i].x + points[i].y } ?: 0
    val fwd = List(4) { points[(tlIndex + it) % 4] } // tl, ?, ?, ?
    val rev = listOf(fwd[0], fwd[3], fwd[2], fwd[1]) // reverse direction, still starts at tl
    // Prefer the variant where second point is on the right side (top-right).
    return if (fwd[1].x >= rev[1].x) fwd else rev
}

private fun reorderToPrevious(previous: List<Point>, current: List<Point>): List<Point> {
    val remaining = current.toMutableList()
    val ordered = mutableListOf<Point>()
    for (p in previous) {
        val idx = remaining.indices.minByOrNull { i -> distance(p, remaining[i]) } ?: 0
        ordered.add(remaining.removeAt(idx))
    }
    return if (isSelfIntersecting(ordered)) sortPoints(ordered) else normalizeQuadOrder(ordered)
}

private fun smoothPoints(old: List<Point>, new: List<Point>, alpha: Double): List<Point> =
    old.zip(new).map { (o, n) ->
        Point(
            o.x * (1.0 - alpha) + n.x * alpha,
            o.y * (1.0 - alpha) + n.y * alpha
        )
    }

private data class Candidate(
    val corners: List<Point>,
    val area: Double
)

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

    val candidates = ArrayList<Candidate>()

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
            // Preview must be strict to avoid drifting to non-document edges.
            if (requireTemporalStability) continue
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

        candidates.add(Candidate(sorted, qArea))
    }

    gray.release()
    edges.release()
    bin.release()
    merged.release()
    closeKernel.release()
    clahe.collectGarbage()

    val best: List<Point>? = if (candidates.isEmpty()) {
        null
    } else if (requireTemporalStability && lastAccepted != null) {
        val prev = lastAccepted!!
        val prevArea = max(quadArea(prev), 1.0)
        val prevCenter = quadCenter(prev)

        // Try to find a match with strict constraints
        val strictMatch = candidates.mapNotNull { candidate ->
            val ordered = reorderToPrevious(prev, candidate.corners)
            val avgMove = averageMovement(prev, ordered)
            if (avgMove > PREVIEW_MAX_AVG_MOVE_PX) return@mapNotNull null

            val centerMove = distance(prevCenter, quadCenter(ordered))
            if (centerMove > PREVIEW_MAX_CENTER_MOVE_PX) return@mapNotNull null

            val areaScale = quadArea(ordered) / prevArea
            if (areaScale !in PREVIEW_MIN_AREA_SCALE..PREVIEW_MAX_AREA_SCALE) return@mapNotNull null

            val score = avgMove + (centerMove * 0.75) + (abs(1.0 - areaScale) * 20.0)
            Pair(ordered, score)
        }.minByOrNull { it.second }?.first

        if (strictMatch != null) {
            strictMatch
        } else {
            // FALLBACK: If no strict match found, check if we should reset
            val now = SystemClock.elapsedRealtime()
            val timeSinceLastUpdate = now - lastAcceptedAtMs
            
            // If we've been showing stale data for too long, accept the best new candidate
            if (timeSinceLastUpdate > 1000L) {  // After 1 second, reset to best candidate
                Log.i(TAG, "No strict match found after ${timeSinceLastUpdate}ms, resetting to best candidate")
                lastAccepted = null  // Reset tracking
                candidates.maxByOrNull { it.area }?.corners
            } else {
                // Keep showing old position briefly
                null
            }
        }
    } else {
        candidates.maxByOrNull { it.area }?.corners
    }

    if (best != null) {
        return if (requireTemporalStability) {
            val now = SystemClock.elapsedRealtime()
            val previewCorners = if (lastAccepted == null) {
                best
            } else {
                smoothPoints(lastAccepted!!, best, PREVIEW_SMOOTH_ALPHA)
            }

            lastAccepted = previewCorners
            lastAcceptedAtMs = now
            Corners(previewCorners, resized.size())
        } else {
            // Capture/crop expects points in original image space.
            val mapped = best.map { Point(it.x * invScale, it.y * invScale) }
            Corners(mapped, frame.size())
        }
    }

    // ---------- 5. Nothing valid ----------
    if (requireTemporalStability && lastAccepted != null) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAcceptedAtMs <= PREVIEW_KEEP_LAST_MS) {
            return Corners(lastAccepted!!, resized.size())
        }
    }

    lastAccepted = null
    lastAcceptedAtMs = 0L
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
    if (points.size != 4) return points

    // 4-point exhaustive ordering to avoid occasional TL/TR flips (bow-tie quads).
    var best: List<Point>? = null
    var bestArea = -1.0
    for (i in points.indices) {
        for (j in points.indices) {
            if (j == i) continue
            for (k in points.indices) {
                if (k == i || k == j) continue
                for (l in points.indices) {
                    if (l == i || l == j || l == k) continue
                    val perm = listOf(points[i], points[j], points[k], points[l])
                    if (isSelfIntersecting(perm)) continue
                    val area = quadArea(perm)
                    if (area > bestArea) {
                        bestArea = area
                        best = perm
                    }
                }
            }
        }
    }

    val simple = best ?: points
    return normalizeQuadOrder(simple)
}
