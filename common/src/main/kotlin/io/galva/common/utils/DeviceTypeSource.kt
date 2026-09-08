package io.galva.common.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

enum class DeviceType(val id: String) {
    PHONE("phone"), TABLET("tablet"), WATCH("watch"), DESKTOP("desktop"), TV("tv"), AUTO("auto"), UNKNOWN(
        "unknown"
    ),
}

internal object DeviceTypeSource {
    /**
     * Detects the device form factor in priority order:
     *  1. UI mode flags (TV, watch, car, desk) — most reliable
     *  2. PackageManager features (watch, automotive) — explicit declarations
     *  3. Smallest-width buckets (tablet vs phone) — last resort
     */
    fun detect(context: Context): DeviceType {
        // 1. UI mode (most authoritative)
        detectFromUiMode(context)?.let { return it }

        // 2. Hardware features
        detectFromFeatures(context)?.let { return it }

        // 3. Screen size heuristic (phone vs tablet)
        return detectFromScreenSize(context)
    }

    private fun detectFromUiMode(context: Context): DeviceType? {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return null
        return when (ui.currentModeType) {
            Configuration.UI_MODE_TYPE_TELEVISION -> DeviceType.TV
            Configuration.UI_MODE_TYPE_WATCH -> DeviceType.WATCH
            Configuration.UI_MODE_TYPE_CAR -> DeviceType.AUTO
            Configuration.UI_MODE_TYPE_DESK -> DeviceType.DESKTOP
            else -> null
        }
    }

    private fun detectFromFeatures(context: Context): DeviceType? {
        val pm = context.packageManager
        return when {
            pm.hasSystemFeature("android.hardware.type.watch") -> DeviceType.WATCH
            pm.hasSystemFeature("android.hardware.type.television") -> DeviceType.TV
            pm.hasSystemFeature("android.hardware.type.automotive") -> DeviceType.AUTO
            pm.hasSystemFeature("android.hardware.type.pc") -> DeviceType.DESKTOP
            else -> null
        }
    }

    /**
     * Screen-based tablet detection. The Android-standard threshold is sw600dp:
     * devices with a smallest-width of 600dp or more are considered tablets.
     */
    private fun detectFromScreenSize(context: Context): DeviceType = try {
        val cfg = context.resources.configuration
        if (cfg.smallestScreenWidthDp >= TABLET_MIN_SW_DP) DeviceType.TABLET
        else DeviceType.PHONE
    } catch (_: Exception) {
        DeviceType.UNKNOWN
    }

    private const val TABLET_MIN_SW_DP = 600
}