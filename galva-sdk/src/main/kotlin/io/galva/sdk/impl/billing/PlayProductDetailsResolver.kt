package io.galva.sdk.impl.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import io.galva.common.logger.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PlayProductDetailsResolver(
    private val billingClient: BillingClient,
    private val connection: PlayBillingConnection,
    private val logger: Logger,
) : ProductDetailsResolver {

    override suspend fun resolve(productIds: List<String>): List<ProductDetails> {
        if (productIds.isEmpty()) return emptyList()

        connection.ensureConnectedWithRetry()                       // ← gate

        val subs = query(productIds, BillingClient.ProductType.SUBS)
        val inapp = query(productIds, BillingClient.ProductType.INAPP)
        return subs + inapp
    }

    private suspend fun query(productIds: List<String>, type: String): List<ProductDetails> =
        callbackFlow {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    productIds.map { id ->
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(type)
                            .build()
                    }
                )
                .build()

            billingClient.queryProductDetailsAsync(params) { result, details ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    trySend(details.productDetailsList)
                } else {
                    logger.warn { "queryProductDetailsAsync($type) failed: ${result.responseCode}" }
                    trySend(emptyList())
                }
            }
            awaitClose()
        }.firstOrNull() ?: emptyList()
}