package io.galva.common.utils

import io.galva.common.InternalGalvaApi
import android.os.Build

@InternalGalvaApi
data class OS(val name: String, val version: String)
@InternalGalvaApi
object OsInfoSource {
    fun read() =OS(name = "Android", version = Build.VERSION.RELEASE.orEmpty())
}