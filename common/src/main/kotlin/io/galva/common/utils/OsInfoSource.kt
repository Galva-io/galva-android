package io.galva.common.utils

import android.os.Build

data class OS(val name: String, val version: String)
object OsInfoSource {
    fun read() =OS(name = "Android", version = Build.VERSION.RELEASE.orEmpty())
}