package com.jayfinava.flutteredgedetection.scan
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceHolder
import android.widget.Toast
import com.jayfinava.flutteredgedetection.EdgeDetectionHandler
import com.jayfinava.flutteredgedetection.REQUEST_CODE
import com.jayfinava.flutteredgedetection.SourceManager
import com.jayfinava.flutteredgedetection.crop.CropActivity
import com.jayfinava.flutteredgedetection.processor.Corners
import com.jayfinava.flutteredgedetection.processor.MrzAutoCaptureProcessor
import com.jayfinava.flutteredgedetection.processor.MrzAutoCaptureResult
import com.jayfinava.flutteredgedetection.processor.cropPicture
import com.jayfinava.flutteredgedetection.processor.processPicture
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Core.ROTATE_90_CLOCKWISE
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.util.Size as SizeB
import org.opencv.core.Rect as CvRect
import org.opencv.core.Point as CvPoint

class ScanPresenter constructor(
    private val context: Context,
    private val iView: IScanView.Proxy,
    private val initialBundle: Bundle
) :
    SurfaceHolder.Callback, Camera.PictureCallback, Camera.PreviewCallback {
    private val TAG: String = "ScanPresenter"
    private var mCamera: Camera? = null
    private val mSurfaceHolder: SurfaceHolder = iView.getSurfaceView().holder
    private val executor: ExecutorService
    private val proxySchedule: Scheduler
    private var busy: Boolean = false
    private var mCameraLensFacing: String? = null
    private var flashEnabled: Boolean = false

    private var mLastClickTime = 0L
    private var shutted: Boolean = true
    private val autoCaptureEnabled: Boolean =
        initialBundle.getBoolean(EdgeDetectionHandler.AUTO_CAPTURE, false)
    private val autoCaptureMinGoodFrames: Int =
        max(1, initialBundle.getInt(EdgeDetectionHandler.AUTO_CAPTURE_MIN_GOOD_FRAMES, 2))
    private val mrzAutoCaptureProcessor = MrzAutoCaptureProcessor(
        context = context,
        requiredStableFrames = autoCaptureMinGoodFrames
    )
    private val autoCaptureTextNoPassport: String =
        initialBundle.getString(EdgeDetectionHandler.AUTO_CAPTURE_TEXT_NO_PASSPORT)
            ?: "Place your passport inside the guide"
    private val autoCaptureTextHoldStill: String =
        initialBundle.getString(EdgeDetectionHandler.AUTO_CAPTURE_TEXT_HOLD_STILL)
            ?: "Hold your position"
    private val autoCaptureTextCapturing: String =
        initialBundle.getString(EdgeDetectionHandler.AUTO_CAPTURE_TEXT_CAPTURING)
            ?: "Capturing..."
    private var pendingAutoCapture = false
    private var autoCaptureInFlight = false
    private var goodFrameStreak = 0
    private var bestPreviewCorners: Corners? = null
    private var bestPreviewScore = 0.0
    private val autoPreviewJpegQuality = 100
    private var autoPreviewMissCount = 0

    init {
        mSurfaceHolder.addCallback(this)
        executor = Executors.newSingleThreadExecutor()
        proxySchedule = Schedulers.from(executor)
    }

    private fun isOpenRecently(): Boolean {
        if (SystemClock.elapsedRealtime() - mLastClickTime < 3000) {
            return true
        }
        mLastClickTime = SystemClock.elapsedRealtime()
        return false
    }

    fun start() {
        busy = false
        pendingAutoCapture = false
        autoCaptureInFlight = false
        shutted = true
        if (autoCaptureEnabled) {
            resetAutoCaptureTracking()
            iView.getPaperRect().setAutoGuideDetected(false)
            iView.setAutoCaptureInstructionText(autoCaptureTextNoPassport)
        }
        val camera = mCamera ?: run {
            Log.i(TAG, "mCamera is null, cannot start preview")
            return
        }
        camera.setPreviewCallback(this)
        camera.startPreview()
    }

    fun stop() {
        val camera = mCamera ?: run {
            Log.i(TAG, "mCamera is null, cannot stop preview")
            return
        }
        camera.setPreviewCallback(null)
        camera.stopPreview()
        busy = false
    }

    val canShut: Boolean get() = shutted

    fun shut() {
        if (isOpenRecently()) {
            Log.i(TAG, "NOT Taking click")
            return
        }
        triggerPictureCapture(autoCapture = false)
    }

    private fun triggerPictureCapture(autoCapture: Boolean) {
        val camera = mCamera ?: run {
            busy = false
            shutted = true
            autoCaptureInFlight = false
            return
        }
        if (!shutted || busy) {
            return
        }
        pendingAutoCapture = autoCapture
        autoCaptureInFlight = autoCapture
        busy = true
        shutted = false
        if (autoCapture) {
            iView.setAutoCaptureInstructionText(autoCaptureTextCapturing)
        }
        Log.i(TAG, "try to focus")
        camera.autoFocus { b, _ ->
            Log.i(TAG, "focus result: $b")
            camera.enableShutterSound(false)
            camera.takePicture(null, null, this)
        }
    }

    fun toggleFlash() {
        try {
            flashEnabled = !flashEnabled
            val parameters = mCamera?.parameters
            parameters?.flashMode =
                if (flashEnabled) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            mCamera?.parameters = parameters
            mCamera?.startPreview()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun updateCamera() {
        if (null == mCamera) {
            return
        }
        mCamera?.stopPreview()
        try {
            mCamera?.setPreviewDisplay(mSurfaceHolder)
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }
        mCamera?.setPreviewCallback(this)
        mCamera?.startPreview()
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private fun getCameraCharacteristics(id: String): CameraCharacteristics {
        return cameraManager.getCameraCharacteristics(id)
    }

    private fun getBackFacingCameraId(): String? {
        for (camID in cameraManager.cameraIdList) {
            val lensFacing =
                getCameraCharacteristics(camID)?.get(CameraCharacteristics.LENS_FACING)!!
            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                mCameraLensFacing = camID
                break
            }
        }
        return mCameraLensFacing
    }

    private fun initCamera() {

        try {
            mCamera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK)
        } catch (e: RuntimeException) {
            e.stackTrace
            Toast.makeText(context, "cannot open camera, please grant camera", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val cameraCharacteristics =
            cameraManager.getCameraCharacteristics(getBackFacingCameraId()!!)

        val display = iView.getCurrentDisplay()
        val point = Point()

        display?.getRealSize(point)

        val displayWidth = minOf(point.x, point.y)
        val displayHeight = maxOf(point.x, point.y)
        val displayRatio = displayWidth.div(displayHeight.toFloat())

        val param = mCamera?.parameters
        val supportedPreviewSizes = param?.supportedPreviewSizes
        val fallbackSize = display?.let {
            getPreviewOutputSize(
                it, cameraCharacteristics, SurfaceHolder::class.java
            )
        }
        val chosenPreviewSize = supportedPreviewSizes?.let {
            chooseBestPreviewSize(it, displayRatio)
        }
        val previewWidth = chosenPreviewSize?.width ?: fallbackSize?.width ?: 1920
        val previewHeight = chosenPreviewSize?.height ?: fallbackSize?.height ?: 1080

        Log.i(TAG, "Selected preview size: ${previewWidth}x${previewHeight}")
        param?.setPreviewSize(previewWidth, previewHeight)
        mSurfaceHolder.setFixedSize(previewWidth, previewHeight)

        val previewRatio = minOf(previewWidth, previewHeight)
            .div(maxOf(previewWidth, previewHeight).toFloat())
        val surfaceView = iView.getSurfaceView()
        val (targetWidth, targetHeight) = if (previewRatio <= 0f) {
            displayWidth to displayHeight
        } else if (displayRatio > previewRatio) {
            (displayHeight * previewRatio).toInt() to displayHeight
        } else {
            displayWidth to (displayWidth / previewRatio).toInt()
        }

        val surfaceParams = surfaceView.layoutParams as? android.widget.RelativeLayout.LayoutParams
            ?: android.widget.RelativeLayout.LayoutParams(surfaceView.layoutParams)
        surfaceParams.width = targetWidth
        surfaceParams.height = targetHeight
        surfaceParams.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT, android.widget.RelativeLayout.TRUE)
        surfaceView.layoutParams = surfaceParams

        // Keep guide overlay aligned with the visible camera preview area.
        val guideView = iView.getPaperRect()
        val guideParams = guideView.layoutParams as? android.widget.RelativeLayout.LayoutParams
            ?: android.widget.RelativeLayout.LayoutParams(guideView.layoutParams)
        guideParams.width = targetWidth
        guideParams.height = targetHeight
        guideParams.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT, android.widget.RelativeLayout.TRUE)
        guideView.layoutParams = guideParams

        val supportPicSize = mCamera?.parameters?.supportedPictureSizes
        supportPicSize?.sortByDescending { it.width.times(it.height) }
        var pictureSize = supportPicSize?.find {
            it.height.toFloat().div(it.width.toFloat()) - previewRatio < 0.01
        }

        if (null == pictureSize) {
            pictureSize = supportPicSize?.get(0)
        }

        if (null == pictureSize) {
            Log.e(TAG, "can not get picture size")
        } else {
            param?.setPictureSize(pictureSize.width, pictureSize.height)
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS) && mCamera!!.parameters.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
        {
            param?.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Log.i(TAG, "enabling autofocus")
        } else {
            Log.i(TAG, "autofocus not available")
        }

        param?.flashMode = Camera.Parameters.FLASH_MODE_OFF

        mCamera?.parameters = param
        mCamera?.setDisplayOrientation(90)
        mCamera?.enableShutterSound(false)
    }

    private fun matrixResizer(sourceMatrix: Mat): Mat {
        val sourceSize: Size = sourceMatrix.size()
        var copied = Mat()
        if (sourceSize.height < sourceSize.width) {
            Core.rotate(sourceMatrix, copied, ROTATE_90_CLOCKWISE)
        } else {
            copied = sourceMatrix
        }
        val copiedSize: Size = copied.size()
        return if (copiedSize.width > ScanConstants.MAX_SIZE.width || copiedSize.height > ScanConstants.MAX_SIZE.height) {
            var useRatio = 0.0
            val widthRatio: Double = ScanConstants.MAX_SIZE.width / copiedSize.width
            val heightRatio: Double = ScanConstants.MAX_SIZE.height / copiedSize.height
            useRatio = if(widthRatio > heightRatio)  widthRatio else heightRatio
            val resizedImage = Mat()
            val newSize = Size(copiedSize.width * useRatio, copiedSize.height * useRatio)
            Imgproc.resize(copied, resizedImage, newSize)
            resizedImage
        } else {
            copied
        }
    }

    private fun chooseBestPreviewSize(
        sizes: List<Camera.Size>,
        targetRatio: Float,
        maxLong: Int = 1920
    ): Camera.Size {
        val normalizedTarget = targetRatio.coerceIn(0.1f, 1.0f)
        var candidates = sizes.map { size ->
            val long = max(size.width, size.height)
            val short = min(size.width, size.height)
            val ratio = short.toFloat() / long.toFloat()
            PreviewSizeCandidate(size, ratio, long * short, long)
        }

        val capped = candidates.filter { it.longEdge <= maxLong }
        if (capped.isNotEmpty()) {
            candidates = capped
        }

        val minDiff = candidates.minOf { abs(it.ratio - normalizedTarget) }
        val ratioCandidates = candidates.filter { abs(it.ratio - normalizedTarget) <= minDiff + 0.01f }

        return ratioCandidates.maxByOrNull { it.area }?.size ?: sizes.first()
    }

    private data class PreviewSizeCandidate(
        val size: Camera.Size,
        val ratio: Float,
        val area: Int,
        val longEdge: Int
    )
    fun detectEdge(pic: Mat) {

        Log.i("height", pic.size().height.toString())
        Log.i("width", pic.size().width.toString())
        val resizedMat = matrixResizer(pic)
        SourceManager.corners = processPicture(
            resizedMat,
            requireTemporalStability = false,
            fastMode = false,
            relaxedValidation = false
        )
        Imgproc.cvtColor(resizedMat, resizedMat, Imgproc.COLOR_RGB2BGRA)
        SourceManager.pic = resizedMat
        val cropIntent = Intent(context, CropActivity::class.java)
        cropIntent.putExtra(EdgeDetectionHandler.INITIAL_BUNDLE, this.initialBundle)
        (context as Activity).startActivityForResult(cropIntent, REQUEST_CODE)
    }

    private fun detectAndSaveAutoCapture(pic: Mat) {
        val resizedMat = matrixResizer(pic)
        val selectedCorners = mapPreviewCornersToSize(resizedMat.size())
        val hasDetectedPassport = selectedCorners?.corners?.size == 4
        Log.i(
            TAG,
            "Auto-capture detection result: detected=$hasDetectedPassport score=${selectedCorners?.let { cornersScore(it) } ?: 0.0}"
        )
        val rgbaMat = Mat()
        Imgproc.cvtColor(resizedMat, rgbaMat, Imgproc.COLOR_RGB2BGRA)
        val outputMat = if (hasDetectedPassport) {
            Log.i(TAG, "Passport detected. Saving cropped auto-capture image.")
            cropPicture(rgbaMat, selectedCorners.corners)
        } else {
            Log.i(TAG, "Passport not detected at capture time. Saving full auto-capture frame.")
            rgbaMat
        }
        saveMatAsJpeg(outputMat)
        if (outputMat !== rgbaMat) {
            outputMat.release()
            rgbaMat.release()
        } else {
            outputMat.release()
        }
    }

    private fun saveMatAsJpeg(mat: Mat) {
        val outputPath = initialBundle.getString(EdgeDetectionHandler.SAVE_TO)
            ?: throw IllegalStateException("Missing save path")
        val bitmap = Bitmap.createBitmap(mat.width(), mat.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap, true)
        FileOutputStream(outputPath).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.flush()
        }
        bitmap.recycle()
    }

    private fun onAutoCaptureCandidate(corners: Corners) {
        autoPreviewMissCount = 0
        val insideGuideZone = iView.getPaperRect().isInsideAutoGuide(corners)
        iView.getPaperRect().setAutoGuideDetected(insideGuideZone)
        val score = cornersScore(corners)
        Log.i(TAG, "Auto preview candidate: insideGuide=$insideGuideZone score=$score")
        if (!insideGuideZone) {
            iView.setAutoCaptureInstructionText(autoCaptureTextNoPassport)
            resetAutoCaptureTracking()
            return
        }

        iView.setAutoCaptureInstructionText(autoCaptureTextHoldStill)
        if (score <= 0.0) {
            resetAutoCaptureTracking()
            return
        }
        goodFrameStreak += 1
        if (score > bestPreviewScore) {
            bestPreviewScore = score
            bestPreviewCorners = cloneCorners(corners)
        }
        if (goodFrameStreak >= autoCaptureMinGoodFrames && !autoCaptureInFlight) {
            triggerPictureCapture(autoCapture = true)
        }
    }

    private fun onAutoCaptureMiss() {
        autoPreviewMissCount += 1
        if (autoPreviewMissCount % 20 == 0) {
            Log.i(TAG, "Auto preview miss count=$autoPreviewMissCount")
        }
        iView.getPaperRect().setAutoGuideDetected(false)
        iView.setAutoCaptureInstructionText(autoCaptureTextNoPassport)
        resetAutoCaptureTracking()
    }

    private fun resetAutoCaptureTracking() {
        goodFrameStreak = 0
        bestPreviewScore = 0.0
        bestPreviewCorners = null
        mrzAutoCaptureProcessor.reset()
    }

    private fun handleMrzAutoCaptureResult(result: MrzAutoCaptureResult, previewSize: Size) {
        if (!result.mrzDetected) {
            onAutoCaptureMiss()
            return
        }

        iView.getPaperRect().setAutoGuideDetected(true)
        iView.setAutoCaptureInstructionText(autoCaptureTextHoldStill)

        result.corners?.let { corners ->
            bestPreviewCorners = Corners(corners, previewSize)
        }

        if (result.stableFrameCount >= autoCaptureMinGoodFrames && !autoCaptureInFlight) {
            triggerPictureCapture(autoCapture = true)
        }
    }

    private fun mapGuideRectToPreview(frame: Mat): CvRect? {
        val guideZone = iView.getPaperRect().getAutoGuideZone()
        if (guideZone.isEmpty) return null

        val viewWidth = iView.getPaperRect().width
        val viewHeight = iView.getPaperRect().height
        if (viewWidth <= 0 || viewHeight <= 0) return null

        val scaleX = frame.width().toFloat() / viewWidth.toFloat()
        val scaleY = frame.height().toFloat() / viewHeight.toFloat()

        val left = (guideZone.left * scaleX).roundToInt()
        val top = (guideZone.top * scaleY).roundToInt()
        val width = (guideZone.width() * scaleX).roundToInt()
        val height = (guideZone.height() * scaleY).roundToInt()

        if (width <= 0 || height <= 0) return null

        val boundedLeft = left.coerceAtLeast(0)
        val boundedTop = top.coerceAtLeast(0)
        val boundedRight = (boundedLeft + width).coerceAtMost(frame.width())
        val boundedBottom = (boundedTop + height).coerceAtMost(frame.height())

        val boundedWidth = boundedRight - boundedLeft
        val boundedHeight = boundedBottom - boundedTop
        if (boundedWidth <= 0 || boundedHeight <= 0) return null

        return CvRect(boundedLeft, boundedTop, boundedWidth, boundedHeight)
    }

    private fun cloneCorners(corners: Corners): Corners {
        val copiedPoints = corners.corners.map { CvPoint(it.x, it.y) }
        return Corners(copiedPoints, Size(corners.size.width, corners.size.height))
    }

    private fun mapPreviewCornersToSize(target: Size): Corners? {
        val previewCorners = bestPreviewCorners ?: return null
        if (previewCorners.size.width <= 0.0 || previewCorners.size.height <= 0.0) {
            return null
        }
        val ratioX = target.width / previewCorners.size.width
        val ratioY = target.height / previewCorners.size.height
        val mapped = previewCorners.corners.map { CvPoint(it.x * ratioX, it.y * ratioY) }
        return Corners(mapped, target)
    }

    private fun pickBestCorners(first: Corners?, second: Corners?): Corners? {
        val candidates = listOfNotNull(first, second)
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { cornersScore(it) }
    }

    private fun cornersScore(corners: Corners): Double {
        if (corners.corners.size != 4) return 0.0
        val area = quadArea(corners.corners)
        val frameArea = corners.size.width * corners.size.height
        if (area <= 0.0 || frameArea <= 0.0) return 0.0
        return area / frameArea
    }

    private fun quadArea(points: List<CvPoint>): Double {
        if (points.size != 4) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            sum += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return kotlin.math.abs(sum) * 0.5
    }

    private fun openAutoCapturePreview() {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            val previewIntent = Intent(activity, AutoCapturePreviewActivity::class.java)
            previewIntent.putExtra(EdgeDetectionHandler.INITIAL_BUNDLE, initialBundle)
            activity.startActivityForResult(previewIntent, REQUEST_CODE)
        }
    }

    private fun finishAutoCaptureWithError(message: String) {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            val errorIntent = Intent().apply {
                putExtra("RESULT", message)
            }
            activity.setResult(EdgeDetectionHandler.ERROR_CODE, errorIntent)
            activity.finish()
        }
    }

    override fun surfaceCreated(p0: SurfaceHolder) {
        initCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
        updateCamera()
    }

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        synchronized(this) {
            mCamera?.stopPreview()
            mCamera?.setPreviewCallback(null)
            mCamera?.release()
            mCamera = null
            val activity = context as? Activity
            val shouldReleaseOcr = activity?.isFinishing == true || activity?.isDestroyed == true
            if (shouldReleaseOcr) {
                mrzAutoCaptureProcessor.release()
            } else {
                mrzAutoCaptureProcessor.reset()
            }
            busy = false
            pendingAutoCapture = false
            autoCaptureInFlight = false
            shutted = true
        }
    }

    @SuppressLint("CheckResult")
    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        Log.i(TAG, "on picture taken")
        val isAutoCapture = pendingAutoCapture
        pendingAutoCapture = false
        Observable.just(p0)
            .subscribeOn(proxySchedule)
            .subscribe({
                try {
                    val pictureSize = p1?.parameters?.pictureSize
                    Log.i(TAG, "picture size: $pictureSize")
                    val mat = Mat(
                        Size(
                            pictureSize?.width?.toDouble() ?: 1920.0,
                            pictureSize?.height?.toDouble() ?: 1080.0
                        ),
                        CvType.CV_8U
                    )
                    mat.put(0, 0, p0)
                    val pic = Imgcodecs.imdecode(mat, Imgcodecs.CV_LOAD_IMAGE_UNCHANGED)
                    Core.rotate(pic, pic, Core.ROTATE_90_CLOCKWISE)
                    mat.release()
                    if (isAutoCapture) {
                        detectAndSaveAutoCapture(pic)
                        openAutoCapturePreview()
                    } else {
                        detectEdge(pic)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Capture processing failed", e)
                    if (isAutoCapture) {
                        finishAutoCaptureWithError(e.message ?: "Auto capture failed")
                    }
                } finally {
                    if (isAutoCapture) {
                        resetAutoCaptureTracking()
                    }
                    autoCaptureInFlight = false
                    pendingAutoCapture = false
                    shutted = true
                    busy = false
                }
            }, { throwable ->
                Log.e(TAG, "Picture decode failed", throwable)
                if (isAutoCapture) {
                    finishAutoCaptureWithError(throwable.message ?: "Auto capture failed")
                    resetAutoCaptureTracking()
                }
                autoCaptureInFlight = false
                pendingAutoCapture = false
                shutted = true
                busy = false
            })
    }

    @SuppressLint("CheckResult")
    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {
        if (busy) {
            return
        }
        busy = true
        try {
            Observable.just(p0)
                .observeOn(proxySchedule)
                .doOnError {}
                .subscribe({
                    val parameters = p1?.parameters
                    val width = parameters?.previewSize?.width
                    val height = parameters?.previewSize?.height
                    val yuv = YuvImage(
                        p0, parameters?.previewFormat ?: 0, width ?: 1080, height
                            ?: 1920, null
                    )
                    val out = ByteArrayOutputStream()
                    val previewWidth = width ?: 1080
                    val previewHeight = height ?: 1920
                    val jpegQuality = if (autoCaptureEnabled) autoPreviewJpegQuality else 100
                    yuv.compressToJpeg(Rect(0, 0, previewWidth, previewHeight), jpegQuality, out)
                    val bytes = out.toByteArray()
                    val decodeOptions = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    if (bitmap == null) {
                        busy = false
                        Log.e(TAG, "Preview frame decode returned null")
                        return@subscribe
                    }
                    val img = Mat()
                    Utils.bitmapToMat(bitmap, img)
                    bitmap.recycle()
                    Core.rotate(img, img, Core.ROTATE_90_CLOCKWISE)
                    try {
                        out.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    if (autoCaptureEnabled) {
                        Observable.create<Pair<MrzAutoCaptureResult, Size>> {
                            try {
                                val guideRect = mapGuideRectToPreview(img)
                                val result = if (guideRect == null) {
                                    MrzAutoCaptureResult(false, 0, null)
                                } else {
                                    mrzAutoCaptureProcessor.process(img, guideRect)
                                }
                                val previewSize = Size(img.width().toDouble(), img.height().toDouble())
                                it.onNext(result to previewSize)
                            } finally {
                                busy = false
                                img.release()
                            }
                        }.observeOn(AndroidSchedulers.mainThread())
                            .subscribe({ (result, previewSize) ->
                                handleMrzAutoCaptureResult(result, previewSize)
                            }, {
                                onAutoCaptureMiss()
                            })
                    } else {
                        Observable.create<Corners> {
                            try {
                                val corner = processPicture(
                                    img,
                                    requireTemporalStability = true,
                                    fastMode = false,
                                    relaxedValidation = false
                                )
                                if (null != corner && corner.corners.size == 4) {
                                    it.onNext(corner)
                                } else {
                                    it.onError(Throwable("paper not detected"))
                                }
                            } finally {
                                busy = false
                                img.release()
                            }
                        }.observeOn(AndroidSchedulers.mainThread())
                            .subscribe({
                                iView.getPaperRect().onCornersDetected(it)
                            }, {
                                iView.getPaperRect().onCornersNotDetected()
                            })
                    }
                }, { throwable ->
                    busy = false
                    Log.e(TAG, throwable.message ?: "Preview processing failed")
                })
        } catch (e: Exception) {
            busy = false
            print(e.message)
        }

    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */

    class SmartSize(width: Int, height: Int) {
        var size = SizeB(width, height)
        var long = max(size.width, size.height)
        var short = min(size.width, size.height)
        override fun toString() = "SmartSize(${long}x${short})"
    }

    /** Standard High Definition size for pictures and video */
    private val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

    /** Returns a [SmartSize] object for the given [Display] */
    private fun getDisplaySmartSize(display: Display): SmartSize {
        val outPoint = Point()
        display.getRealSize(outPoint)
        return SmartSize(outPoint.x, outPoint.y)
    }

    /**
     * Returns the largest available PREVIEW size. For more information, see:
     * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
     * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
     */
    private fun <T> getPreviewOutputSize(
        display: Display,
        characteristics: CameraCharacteristics,
        targetClass: Class<T>,
        format: Int? = null
    ): SizeB {

        // Find which is smaller: screen or 1080p
        val screenSize = getDisplaySmartSize(display)
        val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
        val maxSize = if (hdScreen) SIZE_1080P else screenSize

        // If image format is provided, use it to determine supported sizes; else use target class
        val config = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )!!
        if (format == null)
            assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
        else
            assert(config.isOutputSupportedFor(format))
        val allSizes = if (format == null)
            config.getOutputSizes(targetClass) else config.getOutputSizes(format)

        // Get available sizes and sort them by area from largest to smallest
        val validSizes = allSizes
            .sortedWith(compareBy { it.height * it.width })
            .map { SmartSize(it.width, it.height) }.reversed()

        // Then, get the largest output size that is smaller or equal than our max size
        return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
    }
}
