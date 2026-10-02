package com.opendroidmic

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.google.android.material.color.MaterialColors

/** Shared system-bar policy for Android 8+ and current HyperOS devices. */
abstract class EdgeToEdgeActivity : AppCompatActivity() {
    protected open val systemBarBackgroundColor: Int
        get() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Resolve the actual surface after AppCompat and dynamic colors apply the theme.
        window.setBackgroundDrawable(ColorDrawable(systemBarBackgroundColor))
        val barStyle = if (MaterialColors.isColorLight(systemBarBackgroundColor)) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Prevent the platform/ROM from adding a contrast scrim over our surface.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Reapply after permission dialogs or returning from the scanner. Use public
            // Android APIs; old MIUI dark-mode reflection is unnecessary on minSdk 26+.
            val darkIcons = MaterialColors.isColorLight(systemBarBackgroundColor)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
        }
    }

    protected fun applySafeInsets(view: View, includeIme: Boolean = false) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom
        val insetTypes = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            (if (includeIme) WindowInsetsCompat.Type.ime() else 0)

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            // getInsets takes the maximum of the requested types, so the keyboard
            // and navigation bar are not counted twice. Never accumulate padding.
            val safeArea = windowInsets.getInsets(insetTypes)
            target.setPadding(
                initialLeft + safeArea.left,
                initialTop + safeArea.top,
                initialRight + safeArea.right,
                initialBottom + safeArea.bottom
            )
            windowInsets
        }
        view.doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
}
