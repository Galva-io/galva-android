package io.galva.common.utils

import io.galva.common.InternalGalvaApi
import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager

@InternalGalvaApi
data class Screen(val density: Int, val height: Int, val width: Int)
@InternalGalvaApi
object ScreenSource {
    fun read(context: Context): Screen = try {
         val metrics =  context.resources.displayMetrics
        Screen(density = metrics.densityDpi, height = metrics.heightPixels, width = metrics.widthPixels)
    } catch (_: Exception) {
        Screen(density = 0, height = 0, width = 0)
    }
}