package com.example.georeport.ui.common

import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar

/** One message at a time, with enough room for actionable text and accessibility. */
fun View.showFeedback(message: CharSequence) {
    Snackbar.make(this, message, Snackbar.LENGTH_LONG)
        .setTextMaxLines(5)
        .setAction("Cerrar") { }
        .show()
}

fun View.showFeedback(@StringRes message: Int) = showFeedback(context.getText(message))
