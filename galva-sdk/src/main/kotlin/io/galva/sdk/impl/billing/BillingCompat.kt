package io.galva.sdk.impl.billing

import com.android.billingclient.api.BillingClient

interface BillingCompat {
    fun enableAutoServiceReconnection(builder: BillingClient.Builder): Boolean
}

class PlayBillingCompat : BillingCompat {
    override fun enableAutoServiceReconnection(
        builder: BillingClient.Builder
    ): Boolean {
        return try {
            val method = builder::class.java.getDeclaredMethod(
                "enableAutoServiceReconnection"
            )
            method.invoke(builder)
            true
        } catch (_: NoSuchMethodException) {
            false
        }
    }
}