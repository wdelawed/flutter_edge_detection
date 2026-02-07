package com.jayfinava.flutteredgedetection.processor

import android.content.Context
import java.io.FileNotFoundException
import android.util.Log
import com.googlecode.leptonica.android.ReadFile
import com.googlecode.tesseract.android.TessBaseAPI
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MrzAutoCaptureResult(
    val mrzDetected: Boolean,
    val stableFrameCount: Int,
    val corners: List<Point>?
) {
    val shouldCapture: Boolean
        get() = mrzDetected && stableFrameCount > 0
}

class MrzAutoCaptureProcessor(
    context: Context,
    private val requiredStableFrames: Int = 5,
    private val mrzHeightRatio: Double = 0.38,
    private val targetMrzWidth: Int = 600
) {
    private val tess = TessBaseAPI()
    private val appContext = context.applicationContext
    private var stableCount = 0
    @Volatile private var tessReady = false
    @Volatile private var isReleased = false
    @Volatile private var activeLanguage: String? = null
    private val tessLock = Any()

    private val mrzRegex = Regex("[A-Z0-9<]{24,48}")
    private val whitelist = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"

    init {
        initTesseract()
    }

    fun reset() {
        stableCount = 0
    }

    fun release() {
        synchronized(tessLock) {
            isReleased = true
            if (tessReady) {
                tess.end()
            }
            tessReady = false
        }
    }

    fun process(frame: Mat, guideRect: Rect): MrzAutoCaptureResult {
        if (!tessReady || isReleased) {
            reset()
            return MrzAutoCaptureResult(false, 0, null)
        }
        if (frame.empty()) {
            reset()
            return MrzAutoCaptureResult(false, 0, null)
        }

        val frameRect = Rect(0, 0, frame.width(), frame.height())
        val roiRect = intersectRect(guideRect, frameRect)
        if (roiRect.width <= 10 || roiRect.height <= 10) {
            reset()
            return MrzAutoCaptureResult(false, 0, null)
        }

        val roi = frame.submat(roiRect)
        val mrzRect = extractMrzRegionRect(roi)
        val mrz = roi.submat(mrzRect)
        val gray = Mat()
        val enhanced = Mat()
        val resized = Mat()
        val binary = Mat()
        val binaryInverted = Mat()

        try {
            Imgproc.cvtColor(mrz, gray, Imgproc.COLOR_RGBA2GRAY)

            // Light contrast boost for MRZ text
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, enhanced)
            clahe.collectGarbage()

            // Resize to stabilize OCR input
            val scale = targetMrzWidth.toDouble() / max(1, enhanced.width()).toDouble()
            Imgproc.resize(
                enhanced,
                resized,
                Size(targetMrzWidth.toDouble(), enhanced.height() * scale)
            )

            Imgproc.GaussianBlur(resized, resized, Size(3.0, 3.0), 0.0)
            Imgproc.threshold(
                resized,
                binary,
                0.0,
                255.0,
                Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
            )

            // Ensure black text on white background for OCR
            val mean = Core.mean(binary).`val`[0]
            if (mean < 127.0) {
                Core.bitwise_not(binary, binary)
            }

            Core.bitwise_not(binary, binaryInverted)

            val binaryText = runOcr(binary, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            logRawOcrAttemptResult("binary", binaryText)
            val binaryInvText = runOcr(binaryInverted, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            logRawOcrAttemptResult("binaryInv", binaryInvText)
            val enhancedText = runOcr(enhanced, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            logRawOcrAttemptResult("enhanced", enhancedText)
            val binaryAutoText = runOcr(binary, TessBaseAPI.PageSegMode.PSM_AUTO)
            logRawOcrAttemptResult("binaryAuto", binaryAutoText)

            val attempts = listOf(
                OcrCandidate(
                    source = "binary",
                    rawText = binaryText
                ),
                OcrCandidate(
                    source = "binaryInv",
                    rawText = binaryInvText
                ),
                OcrCandidate(
                    source = "enhanced",
                    rawText = enhancedText
                ),
                OcrCandidate(
                    source = "binaryAuto",
                    rawText = binaryAutoText
                )
            ).map { candidate ->
                candidate.copy(lines = extractMrzLines(candidate.rawText))
            }

            val bestCandidate = attempts.maxByOrNull { scoreCandidate(it) }
                ?: OcrCandidate(source = "none", rawText = "", lines = emptyList())
            val rawText = bestCandidate.rawText

            logOcrBeforeMrzValidation(
                bestCandidate = bestCandidate,
                attempts = attempts
            )

            val lines = bestCandidate.lines
            val detected = lines.size >= 2 && lines.any {
                it.contains("<<") || it.startsWith("P<") || it.count { ch -> ch == '<' } >= 3
            }

            stableCount = if (detected) {
                min(stableCount + 1, max(1, requiredStableFrames))
            } else {
                0
            }
            logMrzOcrResult(
                rawText = rawText,
                lines = lines,
                detected = detected,
                stable = stableCount,
                roiRect = roiRect,
                mrzRect = mrzRect,
                source = bestCandidate.source,
                attempts = attempts
            )

            val corners = listOf(
                Point(roiRect.x.toDouble(), roiRect.y.toDouble()),
                Point((roiRect.x + roiRect.width).toDouble(), roiRect.y.toDouble()),
                Point((roiRect.x + roiRect.width).toDouble(), (roiRect.y + roiRect.height).toDouble()),
                Point(roiRect.x.toDouble(), (roiRect.y + roiRect.height).toDouble())
            )

            return MrzAutoCaptureResult(
                mrzDetected = detected,
                stableFrameCount = stableCount,
                corners = corners
            )
        } finally {
            roi.release()
            mrz.release()
            gray.release()
            enhanced.release()
            resized.release()
            binary.release()
            binaryInverted.release()
        }
    }

    private fun initTesseract() {
        val baseDir = File(appContext.filesDir, "tesseract")
        val tessDataDir = File(baseDir, "tessdata")
        if (!tessDataDir.exists() && !tessDataDir.mkdirs()) {
            Log.e(TAG, "Failed to create tessdata dir: ${tessDataDir.absolutePath}")
            tessReady = false
            return
        }

        val tessVersion = try {
            tess.version
        } catch (e: Exception) {
            "unknown (${e.javaClass.simpleName})"
        }
        Log.i(TAG, "Tesseract library version=$tessVersion")
        val assetPath = "tessdata/$OCR_LANGUAGE.traineddata"
        val trainedData = File(tessDataDir, "$OCR_LANGUAGE.traineddata")
        val copied = copyTrainedData(trainedData, assetPath, overwrite = true)
        val exists = trainedData.exists()
        val size = trainedData.length()
        if (!exists || size == 0L) {
            tessReady = false
            activeLanguage = null
            Log.e(
                TAG,
                "Missing traineddata for $OCR_LANGUAGE at ${trainedData.absolutePath} after copy from $assetPath"
            )
            return
        }
        Log.i(
            TAG,
            "Trying Tesseract init for language=$OCR_LANGUAGE size=${size}B copiedFromAssets=$copied mode=OEM_TESSERACT_ONLY"
        )
        val ok = try {
            tess.init(baseDir.absolutePath, OCR_LANGUAGE, TessBaseAPI.OEM_TESSERACT_ONLY)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Tesseract init threw for language=$OCR_LANGUAGE mode=OEM_TESSERACT_ONLY",
                e
            )
            false
        }
        if (!ok) {
            tessReady = false
            activeLanguage = null
            Log.e(
                TAG,
                "Tesseract init failed for language=$OCR_LANGUAGE mode=OEM_TESSERACT_ONLY. " +
                    "Ensure $OCR_LANGUAGE.traineddata is compatible with tess-two 9.1.0."
            )
            return
        }
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        tess.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, whitelist)
        tess.setVariable("tessedit_char_blacklist", "abcdefghijklmnopqrstuvwxyz")
        tessReady = true
        isReleased = false
        activeLanguage = OCR_LANGUAGE
        Log.i(TAG, "Tesseract initialized successfully language=$OCR_LANGUAGE mode=OEM_TESSERACT_ONLY")
    }

    private fun copyTrainedData(target: File, assetPath: String, overwrite: Boolean): Boolean {
        if (target.exists() && target.length() > 0L && !overwrite) return true
        return try {
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            true
        } catch (e: FileNotFoundException) {
            Log.i(TAG, "Traineddata asset not found: $assetPath")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy traineddata from assets: $assetPath", e)
            false
        }
    }

    private fun runOcr(image: Mat, pageSegMode: Int): String {
        if (!tessReady) return ""
        if (image.empty()) return ""

        val width = image.cols()
        val height = image.rows()
        val channels = image.channels()
        if (width <= 1 || height <= 1) return ""
        if (channels != 1) return ""

        val imageDataSize = width * height
        if (imageDataSize <= 0) return ""

        val imageData = ByteArray(imageDataSize)
        image.get(0, 0, imageData)
        if (imageData.isEmpty()) return ""
        val pix = ReadFile.readBytes8(imageData, width, height) ?: return ""

        try {
            synchronized(tessLock) {
                if (!tessReady || isReleased) return ""
                tess.setPageSegMode(pageSegMode)
                tess.setImage(pix)
                val text = tess.getUTF8Text() ?: ""
                tess.clear()
                return text
            }
        } finally {
            pix.recycle()
        }
    }

    private fun logMrzOcrResult(
        rawText: String,
        lines: List<String>,
        detected: Boolean,
        stable: Int,
        roiRect: Rect,
        mrzRect: Rect,
        source: String,
        attempts: List<OcrCandidate>
    ) {
        if (rawText.isBlank()) return
        val stableRequired = max(1, requiredStableFrames)
        val compactRaw = compactForLog(rawText)
        val attemptsSummary = attempts.joinToString(
            prefix = "[",
            postfix = "]"
        ) { attempt ->
            "${attempt.source}:raw=${attempt.rawText.length},lines=${attempt.lines.size}"
        }
        Log.i(
            TAG,
            "MRZ OCR detected=$detected stable=$stable/$stableRequired " +
                "lang=${activeLanguage ?: OCR_LANGUAGE} " +
                "source=$source attempts=$attemptsSummary " +
                "roi=${roiRect.width}x${roiRect.height} mrz=${mrzRect.width}x${mrzRect.height} " +
                "raw=\"$compactRaw\" lines=${if (lines.isEmpty()) "[]" else lines.joinToString(prefix = "[", postfix = "]")}"
        )
    }

    private fun logOcrBeforeMrzValidation(
        bestCandidate: OcrCandidate,
        attempts: List<OcrCandidate>
    ) {
        if (bestCandidate.rawText.isBlank()) return
        val attemptTexts = attempts.joinToString(prefix = "[", postfix = "]") { attempt ->
            "${attempt.source}=\"${compactForLog(attempt.rawText, 120)}\""
        }
        Log.i(
            TAG,
            "MRZ OCR pre-validation bestSource=${bestCandidate.source} " +
                "bestRaw=\"${compactForLog(bestCandidate.rawText)}\" " +
                "attemptRaw=$attemptTexts"
        )
    }

    private fun logRawOcrAttemptResult(source: String, rawText: String) {
        val compact = if (rawText.isBlank()) "<empty>" else compactForLog(rawText)
        Log.i(TAG, "MRZ OCR raw source=$source len=${rawText.length} text=\"$compact\"")
    }

    private fun compactForLog(value: String, maxLen: Int = 240): String {
        val flattened = value
            .replace('\n', '|')
            .replace('\r', '|')
            .trim()
        return if (flattened.length <= maxLen) {
            flattened
        } else {
            flattened.take(maxLen) + "..."
        }
    }

    private fun extractMrzLines(raw: String): List<String> {
        return raw.uppercase()
            .split('\n', '\r')
            .map { it.trim().replace(" ", "") }
            .map { line ->
                line.filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }
            }
            .filter { it.length in 24..48 && mrzRegex.matches(it) }
    }

    private fun scoreCandidate(candidate: OcrCandidate): Int {
        if (candidate.rawText.isBlank()) return 0
        val markerBonus = if (candidate.lines.any { it.contains("<<") }) 120 else 0
        val lineBonus = candidate.lines.sumOf { it.length }
        val rawBonus = min(candidate.rawText.length, 120) / 3
        return markerBonus + lineBonus + rawBonus
    }

    private fun extractMrzRegionRect(roi: Mat): Rect {
        val mrzHeight = max(1, (roi.height() * mrzHeightRatio).roundToInt())
        val top = max(0, roi.height() - mrzHeight)
        return Rect(0, top, roi.width(), mrzHeight)
    }

    private fun intersectRect(a: Rect, b: Rect): Rect {
        val left = max(a.x, b.x)
        val top = max(a.y, b.y)
        val right = min(a.x + a.width, b.x + b.width)
        val bottom = min(a.y + a.height, b.y + b.height)
        val width = max(0, right - left)
        val height = max(0, bottom - top)
        return Rect(left, top, width, height)
    }

    companion object {
        private const val TAG = "MrzAutoCapture"
        private const val OCR_LANGUAGE = "ocrb"
    }

    private data class OcrCandidate(
        val source: String,
        val rawText: String,
        val lines: List<String> = emptyList()
    )
}
