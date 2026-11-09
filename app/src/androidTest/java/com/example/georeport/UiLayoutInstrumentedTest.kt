package com.example.georeport

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import java.io.File

/** Inflate the actual XML with M3 and exercise compact, large-text and tablet layouts. */
@RunWith(AndroidJUnit4::class)
class UiLayoutInstrumentedTest {
    @Test fun formsInflateAndMeasureAcrossScreenSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            for ((widthDp, scale) in listOf(320 to 1f, 360 to 2f, 800 to 1f)) {
                val configuration = Configuration(base.resources.configuration).apply {
                    screenWidthDp = widthDp; fontScale = scale
                }
                val context = ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_GeoREport)
                val density = context.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                val height = (800 * density).toInt()
                for (layout in listOf(R.layout.activity_login, R.layout.activity_register, R.layout.activity_home)) {
                    val root = LayoutInflater.from(context).inflate(layout, null)
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    // TextInputLayout reserves icon space after its first measurement.
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    assertTrue(root.measuredWidth == width)
                    val buttonId = when (layout) {
                        R.layout.activity_login -> R.id.btnLogin
                        R.layout.activity_register -> R.id.btnBack
                        else -> R.id.btnLogout
                    }
                    assertTrue("Touch target must be at least 48dp", root.findViewById<View>(buttonId).measuredHeight >= 48 * density - 1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    val name = context.resources.getResourceEntryName(layout)
                    File(base.cacheDir, "${name}_${widthDp}_${scale}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    root.findViewById<MapView>(R.id.mapView)?.onDetach()
                }
            }
        }
    }
}
