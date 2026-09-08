package io.galva.sdk.impl.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import io.galva.common.logger.Logger

class BillingClientFactory(
    private val context: Context,
    private val listener: PurchasesUpdatedListener,
    private val logger: Logger,
    private val compat: BillingCompat = PlayBillingCompat()
) {
    fun create(): BillingClient {
        val builder = BillingClient.newBuilder(context.applicationContext)
            .setListener(listener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enablePrepaidPlans()
                    .enableOneTimeProducts().build()
            )
        if (compat.enableAutoServiceReconnection(builder)) {
            logger.info { "Enabled auto service reconnection" }
        }else{
            logger.info { "Auto service reconnection not supported" }
        }
        return builder.build()
    }
}