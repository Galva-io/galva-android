package io.galva.common.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceUtils {
    @JvmStatic
    fun loadDeviceData(context: Context): DeviceData {
        return DeviceData(
            id = androidId(context),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            name = Build.DEVICE.orEmpty(),
            deviceType = DeviceTypeSource.detect(context).id,
            version = Build.VERSION.RELEASE.orEmpty(),
        )
    }

    /** `Settings.Secure.ANDROID_ID` is a stable, app-scoped identifier on API 26+. */
    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    } catch (_: Exception) {
        ""
    }
}

data class DeviceData(
    val id: String,
    val manufacturer: String,
    val model: String,
    val name: String,
    val deviceType: String,
    val version: String
)