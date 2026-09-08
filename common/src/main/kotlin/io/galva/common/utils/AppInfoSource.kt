package io.galva.common.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
object AppInfoSource {
    fun read(context: Context): App? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION") info.versionCode.toString()
        }
        App(
            build = versionCode,
            name = pm.getApplicationLabel(info.applicationInfo!!).toString(),
            namespace = context.packageName,
            version = info.versionName ?: "",
        )
    } catch (_: Exception) {
        null
    }
}

data class App(val build: String,val name: String,val namespace: String,val version: String)
