package io.galva.common.utils

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

data class Screen(val density: Int, val height: Int, val width: Int)
object ScreenSource {
    fun read(context: Context): Screen = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
        }
        Screen(density = metrics.densityDpi, height = metrics.heightPixels, width = metrics.widthPixels)
    } catch (_: Exception) {
        Screen(density = 0, height = 0, width = 0)
    }
}