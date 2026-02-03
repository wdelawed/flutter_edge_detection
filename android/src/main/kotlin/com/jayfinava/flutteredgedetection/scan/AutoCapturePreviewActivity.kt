package com.jayfinava.flutteredgedetection.scan

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
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

        findViewById<Button>(R.id.button_retake).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        findViewById<Button>(R.id.button_next).setOnClickListener {
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
}
