package io.galva.sdk.impl.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import io.galva.billing.model.BillingConnection
import io.galva.common.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A Play purchase, reduced to what Galva reports about it. */
internal data class PlayTransaction(
    val orderId: String?,
    val purchaseToken: String,
    val purchaseTime: Long,
    /** `Purchase.PurchaseState.PURCHASED`, as opposed to pending. */
    val purchased: Boolean,
)

internal fun Purchase.toPlayTransaction() = PlayTransaction(
    orderId = orderId,
    purchaseToken = purchaseToken,
    purchaseTime = purchaseTime,
    purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
)

/** The Play account's purchases, as Galva's own billing client sees them. */
internal interface PlayPurchases {
    /** The purchases of every successful `onPurchasesUpdated`. */
    val updates: Flow<List<PlayTransaction>>

    /**
     * The account's current purchases: active subscriptions and unconsumed one-time products,
     * whichever billing client made them. Empty when Play can't be reached.
     */
    suspend fun query(): List<PlayTransaction>
}

internal class PlayPurchaseSource(
    private val billingClient: BillingClient,
    private val connection: BillingConnection,
    private val purchaseEvents: PlayPurchaseEvents,
    private val logger: Logger,
) : PlayPurchases {

    override val updates: Flow<List<PlayTransaction>> = flow {
        val events = purchaseEvents.subscribe()
        try {
            for (event in events) {
                val purchases = event.purchases
                if (event.result.responseCode == BillingClient.BillingResponseCode.OK && !purchases.isNullOrEmpty()) {
                    emit(purchases.map { it.toPlayTransaction() })
                }
            }
        } finally {
            events.close()
        }
    }

    override suspend fun query(): List<PlayTransaction> = try {
        connection.ensureConnected()
        PRODUCT_TYPES.flatMap { type ->
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            val result = billingClient.queryPurchasesAsync(params)
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                result.purchasesList.map { it.toPlayTransaction() }
            } else {
                logger.debug { "queryPurchases($type) failed: ${result.billingResult.responseCode}" }
                emptyList()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // DefaultBillingManager already warns when Play Billing is unavailable.
        logger.debug(t) { "Play purchases unavailable" }
        emptyList()
    }

    private companion object {
        val PRODUCT_TYPES = listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)
    }
}
