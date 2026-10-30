package com.example.georeport.ui.common

import android.graphics.Color
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.example.georeport.R

/** Keep forms inside the usable window, including when resized or the keyboard opens. */
fun AppCompatActivity.fitScreenContent(root: View) {
    // All three screens use a light background, also in the current night theme.
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY)
    )
    val initialLeft = root.paddingLeft
    val initialTop = root.paddingTop
    val initialRight = root.paddingRight
    val initialBottom = root.paddingBottom
    val maxWidth = resources.getDimensionPixelSize(R.dimen.screen_content_max_width)

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        )
        val availableWidth = (view.width - safe.left - safe.right - initialLeft - initialRight)
            .coerceAtLeast(0)
        val extra = (availableWidth - maxWidth).coerceAtLeast(0)
        view.setPadding(
            initialLeft + safe.left + extra / 2,
            initialTop + safe.top,
            initialRight + safe.right + extra - extra / 2,
            initialBottom + safe.bottom
        )
        // The root has already accounted for these spaces; do not apply them twice.
        WindowInsetsCompat.CONSUMED
    }
    root.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
        if (right - left != oldRight - oldLeft) ViewCompat.requestApplyInsets(view)
    }
    root.doOnAttach { ViewCompat.requestApplyInsets(it) }
}
