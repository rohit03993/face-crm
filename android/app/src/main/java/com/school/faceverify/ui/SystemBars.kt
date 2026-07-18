package com.school.faceverify.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keeps content clear of the status bar / notch and navigation bar.
 */
object SystemBars {
    fun apply(
        activity: Activity,
        root: View,
        extraTopDp: Int = 10,
        extraBottomDp: Int = 12,
    ) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowInsetsControllerCompat(activity.window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = view.resources.displayMetrics.density
            val extraTop = (extraTopDp * density).toInt()
            val extraBottom = (extraBottomDp * density).toInt()
            view.setPadding(
                view.paddingLeft,
                bars.top + extraTop,
                view.paddingRight,
                bars.bottom + extraBottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
