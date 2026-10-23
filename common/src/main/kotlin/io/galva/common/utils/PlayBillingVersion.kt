package io.galva.common.utils

import io.galva.common.InternalGalvaApi

/** Detects whether the app ships a Play Billing Library the SDK can use (8.0+). */
@InternalGalvaApi
object PlayBillingVersion {
    private const val BILLING_CLIENT = "com.android.billingclient.api.BillingClient"

    /** Introduced in Play Billing Library 8.0 (`queryProductDetailsAsync` result type). */
    private const val ADDED_IN_8_0 = "com.android.billingclient.api.QueryProductDetailsResult"

    /** True when any Play Billing Library is on the classpath. */
    val isPresent: Boolean by lazy { hasClass(BILLING_CLIENT) }

    /** True when the Play Billing Library is 8.0 or newer. */
    val isSupported: Boolean by lazy { hasClass(ADDED_IN_8_0) }

    private fun hasClass(name: String): Boolean = try {
        Class.forName(name); true
    } catch (e: ClassNotFoundException) { false }
}
