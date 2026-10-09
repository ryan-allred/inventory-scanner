package com.example.pokemoninventory

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal fun View.applySafeAreaInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}
