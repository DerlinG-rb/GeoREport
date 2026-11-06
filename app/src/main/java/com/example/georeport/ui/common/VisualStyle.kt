package com.example.georeport.ui.common

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.georeport.R
import com.google.android.material.button.MaterialButton

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun TextView.applyGeoText(size: Float = 16f) {
    textSize = size
    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
    typeface = Typeface.create("sans-serif", if (size >= 20f) Typeface.BOLD else Typeface.NORMAL)
    setLineSpacing(context.dp(3).toFloat(), 1f)
    setPadding(0, context.dp(8), 0, context.dp(8))
}

fun MaterialButton.applyGeoButton() {
    isAllCaps = false
    textSize = 16f
    minimumHeight = context.dp(52)
    cornerRadius = context.dp(12)
    setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
}
