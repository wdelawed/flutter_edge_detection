package com.jayfinava.flutteredgedetection.scan

import android.app.Activity
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.MenuItem
import android.widget.Button
import android.widget.ImageView
import com.jayfinava.flutteredgedetection.EdgeDetectionHandler
import com.jayfinava.flutteredgedetection.R
import com.jayfinava.flutteredgedetection.base.BaseActivity
import java.io.File

class AutoCapturePreviewActivity : BaseActivity() {
    private lateinit var initialBundle: Bundle

    override fun provideContentViewId(): Int = R.layout.activity_auto_capture_preview

    override fun initPresenter() {
        // No presenter required for static preview/CTA flow.
    }

    override fun prepare() {
        initialBundle = intent.getBundleExtra(EdgeDetectionHandler.INITIAL_BUNDLE) as Bundle

        title = getString(R.string.preview)

        val savePath = initialBundle.getString(EdgeDetectionHandler.SAVE_TO)
        if (savePath.isNullOrBlank() || !File(savePath).exists()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val bitmap = BitmapFactory.decodeFile(savePath)
        findViewById<ImageView>(R.id.preview_image).setImageBitmap(bitmap)

        val retakeButton = findViewById<Button>(R.id.button_retake)
        val nextButton = findViewById<Button>(R.id.button_next)

        applyPreviewButtonStyle(retakeButton)
        applyPreviewButtonStyle(nextButton)

        retakeButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        nextButton.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            setResult(Activity.RESULT_CANCELED)
            finish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun applyPreviewButtonStyle(button: Button) {
        val textColor = parseColor(
            initialBundle.getString(EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_BUTTON_TEXT_COLOR),
            Color.WHITE
        )
        val textSizeSp =
            initialBundle.getDouble(EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_BUTTON_TEXT_SIZE, 16.0)
        val horizontalPaddingDp = initialBundle.getDouble(
            EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_BUTTON_HORIZONTAL_PADDING,
            16.0
        )
        val verticalPaddingDp = initialBundle.getDouble(
            EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_BUTTON_VERTICAL_PADDING,
            10.0
        )
        val backgroundColor = parseColor(
            initialBundle.getString(EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_BUTTON_BACKGROUND_COLOR),
            Color.parseColor("#73000000")
        )
        val radiusDp =
            initialBundle.getDouble(EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_BUTTON_BORDER_RADIUS, 12.0)

        val density = resources.displayMetrics.density
        val horizontalPaddingPx = (horizontalPaddingDp * density).toInt()
        val verticalPaddingPx = (verticalPaddingDp * density).toInt()
        val cornerRadiusPx = radiusDp.toFloat() * density

        button.setTextColor(textColor)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
        button.setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(backgroundColor)
        }
    }

    private fun parseColor(rawColor: String?, fallback: Int): Int {
        if (rawColor.isNullOrBlank()) return fallback
        return try {
            Color.parseColor(rawColor)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }
}
