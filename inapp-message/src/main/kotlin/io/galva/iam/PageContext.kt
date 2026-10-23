package io.galva.iam

import io.galva.common.InternalGalvaApi
import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@InternalGalvaApi
data class PageContext(
    val bridgeProtocol: String,
    val sdkVersion: String,
    val platform: String,
    val appVersion: String,
    val appBuild: String,
    val pushAuthorization: String,
    val locale: String,
    val appColorScheme: String = "system",
    val storefrontCountryCode: String,
    val safeArea: SafeArea? = null,
    /** `obfuscatedAccountId` attached to Play purchases for this identity (iOS: `appAccountToken`). */
    val appAccountToken: String? = null,
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@InternalGalvaApi
data class SafeArea(val top: Int, val left: Int, val bottom: Int, val right: Int)