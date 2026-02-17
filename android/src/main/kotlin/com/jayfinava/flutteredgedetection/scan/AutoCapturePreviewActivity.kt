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
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jayfinava.flutteredgedetection.EdgeDetectionHandler
import com.jayfinava.flutteredgedetection.R
import com.jayfinava.flutteredgedetection.base.BaseActivity
import java.io.File

class AutoCapturePreviewActivity : BaseActivity() {
    private lateinit var initialBundle: Bundle

    private enum class PreviewButtonType {
        RETAKE,
        NEXT
    }

    private data class PreviewButtonStyleKeys(
        val textColorKey: String,
        val textSizeKey: String,
        val horizontalPaddingKey: String,
        val verticalPaddingKey: String,
        val backgroundColorKey: String,
        val borderRadiusKey: String
    )

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

        retakeButton.text = resolveButtonText(
            initialBundle.getString(EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_RETAKE_BUTTON_TEXT),
            getString(R.string.retake)
        )
        nextButton.text = resolveButtonText(
            initialBundle.getString(EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_NEXT_BUTTON_TEXT),
            getString(R.string.next)
        )

        applyBottomInsetForActions()
        applyPreviewButtonStyle(retakeButton, PreviewButtonType.RETAKE)
        applyPreviewButtonStyle(nextButton, PreviewButtonType.NEXT)

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

    private fun applyPreviewButtonStyle(button: Button, type: PreviewButtonType) {
        val keys = getPreviewButtonStyleKeys(type)

        val textColor = parseColor(
            initialBundle.getString(keys.textColorKey),
            Color.WHITE
        )
        val textSizeSp = initialBundle.getDouble(keys.textSizeKey, 16.0)
        val horizontalPaddingDp = initialBundle.getDouble(
            keys.horizontalPaddingKey,
            16.0
        )
        val verticalPaddingDp = initialBundle.getDouble(
            keys.verticalPaddingKey,
            10.0
        )
        val backgroundColor = parseColor(
            initialBundle.getString(keys.backgroundColorKey),
            Color.parseColor("#73000000")
        )
        val radiusDp = initialBundle.getDouble(keys.borderRadiusKey, 12.0)

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

    private fun getPreviewButtonStyleKeys(type: PreviewButtonType): PreviewButtonStyleKeys =
        when (type) {
            PreviewButtonType.RETAKE -> PreviewButtonStyleKeys(
                textColorKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_RETAKE_BUTTON_TEXT_COLOR,
                textSizeKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_RETAKE_BUTTON_TEXT_SIZE,
                horizontalPaddingKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_RETAKE_BUTTON_HORIZONTAL_PADDING,
                verticalPaddingKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_RETAKE_BUTTON_VERTICAL_PADDING,
                backgroundColorKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_RETAKE_BUTTON_BACKGROUND_COLOR,
                borderRadiusKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_RETAKE_BUTTON_BORDER_RADIUS
            )

            PreviewButtonType.NEXT -> PreviewButtonStyleKeys(
                textColorKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_NEXT_BUTTON_TEXT_COLOR,
                textSizeKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_NEXT_BUTTON_TEXT_SIZE,
                horizontalPaddingKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_NEXT_BUTTON_HORIZONTAL_PADDING,
                verticalPaddingKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_NEXT_BUTTON_VERTICAL_PADDING,
                backgroundColorKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_NEXT_BUTTON_BACKGROUND_COLOR,
                borderRadiusKey = EdgeDetectionHandler.AUTO_CAPTURE_PREVIEW_NEXT_BUTTON_BORDER_RADIUS
            )
        }

    private fun parseColor(rawColor: String?, fallback: Int): Int {
        if (rawColor.isNullOrBlank()) return fallback
        return try {
            Color.parseColor(rawColor)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }

    private fun resolveButtonText(rawText: String?, fallbackText: String): String {
        return rawText?.takeIf { it.isNotBlank() } ?: fallbackText
    }

    private fun applyBottomInsetForActions() {
        val actionsContainer = findViewById<LinearLayout>(R.id.preview_actions)
        val initialBottomPadding = actionsContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(actionsContainer) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                initialBottomPadding + bottomInset
            )
            insets
        }
        ViewCompat.requestApplyInsets(actionsContainer)
    }
}
