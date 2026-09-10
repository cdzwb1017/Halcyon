package com.ella.music.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ella.music.data.SettingsManager

// Window-scoped override survives Activity focus/theme callbacks while the player is visible.
private val playerImmersiveWindows = java.util.WeakHashMap<Window, Boolean>()

internal fun Window.setPlayerImmersiveOverride(enabled: Boolean) {
    if (enabled) playerImmersiveWindows[this] = true else playerImmersiveWindows.remove(this)
}

internal fun Window.applyHalcyonSystemBars(
    mode: Int,
    reserveSpace: Boolean = SettingsManager.DEFAULT_SYSTEM_BARS_RESERVE_SPACE
) {
    val effectiveMode = if (playerImmersiveWindows[this] == true) SettingsManager.SYSTEM_BARS_MODE_HIDE_BOTH else mode
    // Always let the Compose root paint the full window. The reserveSpace choice is applied by
    // the normal content layers using stable insets, while full-window surfaces such as the
    // player background remain behind the hidden bars instead of exposing the platform window
    // background around the app.
    WindowCompat.setDecorFitsSystemWindows(this, false)
    // WindowCompat maps this to the platform layout flags on older Android releases, but a few
    // HyperOS builds restore decorView.systemUiVisibility when the status-bar disable flag is
    // changed. Re-assert the layout bits so a hidden bar never turns into a black/white strip.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        decorView.systemUiVisibility = decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    statusBarColor = Color.TRANSPARENT
    navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        setNavigationBarDividerColor(Color.TRANSPARENT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isNavigationBarContrastEnforced = false
    }
    val controller = WindowInsetsControllerCompat(this, decorView)
    // System bars are controlled only through the platform Insets API. In particular, do not
    // mutate HyperOS global settings here: hiding this app's bars must not require Shizuku and
    // must not change the status/navigation bar state of other applications.
    controller.show(WindowInsetsCompat.Type.systemBars())
    if (effectiveMode != SettingsManager.SYSTEM_BARS_MODE_SHOW_BOTH) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    when (effectiveMode) {
        SettingsManager.SYSTEM_BARS_MODE_HIDE_STATUS ->
            controller.hide(WindowInsetsCompat.Type.statusBars())
        SettingsManager.SYSTEM_BARS_MODE_HIDE_NAVIGATION ->
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        SettingsManager.SYSTEM_BARS_MODE_HIDE_BOTH ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
internal fun ApplyHalcyonSystemBarsToCurrentWindow() {
    val view = LocalView.current
    val context = LocalContext.current
    val mode by SettingsManager.getInstance(context).systemBarsMode.collectAsState(
        initial = SettingsManager.SYSTEM_BARS_MODE_SHOW_BOTH
    )
    val reserveSpace by SettingsManager.getInstance(context).systemBarsReserveSpace.collectAsState(
        initial = SettingsManager.DEFAULT_SYSTEM_BARS_RESERVE_SPACE
    )
    DisposableEffect(view, mode, reserveSpace) {
        view.findHostWindow()?.applyHalcyonSystemBars(mode, reserveSpace)
        onDispose {
            // WindowBottomSheet owns a separate window. Restore the activity window immediately
            // when it disappears so gesture navigation never inherits the sheet's white bar.
            context.findActivity()?.window?.applyHalcyonSystemBars(mode, reserveSpace)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun View.findHostWindow(): Window? {
    var current: ViewParent? = parent
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return (context as? Activity)?.window
}
