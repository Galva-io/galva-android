package io.galva.common.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager

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

    fun getDetectedCountry(context: Context, defaultCountryIsoCode: String): String {
        return detectSIMCountry(context)
            ?: detectNetworkCountry(context)
            ?: detectLocaleCountry(context)
            ?: defaultCountryIsoCode
    }

    private fun detectSIMCountry(context: Context): String? {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            return telephonyManager.simCountryIso
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun detectNetworkCountry(context: Context): String? {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            return telephonyManager.networkCountryIso
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getDetectedLanguage(context: Context, defaultLanguageIsoCode: String): String {
        return detectLocaleLanguage(context)
            ?: defaultLanguageIsoCode
    }
    private fun detectLocaleCountry(context: Context): String? {
        try {
            val localeLanguage  = context.resources.configuration.locales[0].country
            return localeLanguage
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
    private fun detectLocaleLanguage(context: Context): String? {
        try {
            val localeLanguage  = context.resources.configuration.locales[0].toLanguageTag()
            return localeLanguage
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
        return null
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