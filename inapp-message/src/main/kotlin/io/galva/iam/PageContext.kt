package io.galva.iam

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
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
    val safeArea: SafeArea? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class SafeArea(val top: Int, val left: Int, val bottom: Int, val right: Int)