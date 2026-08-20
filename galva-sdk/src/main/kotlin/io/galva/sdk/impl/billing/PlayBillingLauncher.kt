package io.galva.sdk.impl.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import io.galva.billing.BillingLauncher
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.Product
import io.galva.billing.model.ProductType
import io.galva.billing.store.CatalogStore
import io.galva.billing.store.EntitlementStore
import io.galva.common.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class PlayBillingLauncher(
    private val billingClient: BillingClient,
    private val connection: PlayBillingConnection,
    private val cache: ProductDetailsCache,
    private val catalogStore: CatalogStore,
    private val purchaseEvents: PlayPurchaseEvents,
    private val logger: Logger,
    private val entitlementStore: EntitlementStore,
    private val purchaseTimeout: Duration = 5.minutes,

    ) : BillingLauncher {
    private var storefrontCountryCode: String? = null
    override suspend fun loadStorefrontCountryCode(): String? {
        if (storefrontCountryCode != null) {
            return storefrontCountryCode
        }
        withTimeoutOrNull(purchaseTimeout) {
            connection.ensureConnectedWithRetry()
        }
        return suspendCancellableCoroutine { continuation ->
            billingClient.getBillingConfigAsync(
                GetBillingConfigParams.newBuilder().build(),
                { response, result ->
                    if (response.responseCode == BillingClient.BillingResponseCode.OK) {
                        storefrontCountryCode = result?.countryCode
                        logger.debug { "Loaded storefront country code: $storefrontCountryCode" }
                        continuation.resume(storefrontCountryCode)
                    } else {
                        logger.debug { "Failed to load storefront country code: ${response.debugMessage}" }
                        continuation.resume(null)
                    }
                })
            continuation.invokeOnCancellation {
                logger.debug { "loadStorefrontCountryCode was cancelled" }
            }
        }
    }

    override fun launch(
        activity: Activity, productId: String, offerToken: String, obfuscatedAccountId: String?
    ): Flow<BillingLaunchState> = flow {
        emit(BillingLaunchState.Launching(productId, offerToken))
        try {
            connection.ensureConnectedWithRetry()
        } catch (t: Throwable) {
            emit(
                BillingLaunchState.Failed(
                    productId, offerToken,
                    "Billing not connected: ${t.message}", cause = t,
                )
            )
            return@flow
        }

        var productDetails = cache.get(productId)
        if (productDetails == null) {
            val localProduct = catalogStore.getProduct(productId)
            if (localProduct == null) {
                emit(
                    BillingLaunchState.Failed(
                        productId, offerToken,
                        "ProductDetails missing for $productId — product not found in catalog",
                    )
                )
                return@flow
            } else {
                productDetails = loadProductDetails(localProduct)
            }
        }
        if (productDetails != null) {
            val productParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken).build()
            val currentEntitlement = entitlementStore.get()
            val flowParams =
                BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams))
                    .apply {
                        if(productDetails.productType == BillingClient.ProductType.SUBS){
                            if(currentEntitlement != null){
                                val oldSubscription  =cache.get(currentEntitlement.productId)
                                if(oldSubscription?.productType == BillingClient.ProductType.SUBS){
                                    logger.debug { "Upgrading from ${currentEntitlement.productId} to $productId" }
                                    setSubscriptionUpdateParams(
                                        BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                            .setOldPurchaseToken(currentEntitlement.purchaseToken)
                                            .build()
                                    )
                                }
                            }
                        }
                    }
                    .apply {
                        if(currentEntitlement == null || productDetails.productType == BillingClient.ProductType.INAPP){
                            obfuscatedAccountId?.let { setObfuscatedAccountId(it) }
                        }
                    }.build()
            val eventChannel = purchaseEvents.subscribe()
            try {
                val launchResult = withContext(Dispatchers.Main.immediate) {
                    billingClient.launchBillingFlow(activity, flowParams)
                }
                logger.debug {
                    "launchBillingFlow returned response code ${launchResult.responseCode} for $productId"
                }
                if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    emit(
                        BillingLaunchState.Failed(
                            productId, offerToken,
                            "Launch failed: ${launchResult.debugMessage}",
                            responseCode = launchResult.responseCode,
                        )
                    )
                    return@flow
                }

                emit(BillingLaunchState.Showing(productId, offerToken))
                logger.debug { "Sheet shown for $productId" }
                // Wait for a purchase event matching this product
                val event = withTimeoutOrNull(purchaseTimeout) {
                    consumeUntilMatch(eventChannel, productId)
                }
                if (event == null) {
                    emit(
                        BillingLaunchState.Failed(
                            productId, offerToken,
                            "Purchase flow timed out after $purchaseTimeout",
                        )
                    )
                    return@flow
                }

                emit(mapToState(event, productId, offerToken))
            }finally {
                eventChannel.close()
            }
        } else {
            emit(
                BillingLaunchState.Failed(
                    productId, offerToken,
                    "ProductDetails missing for $productId — product not found in catalog",
                )
            )
        }


    }

    /**
     * Reads events from [channel] until one matches [productId]. Non-matching
     * events (e.g., from concurrent launches of different SKUs) are skipped.
     * Returns null only when the channel closes (shouldn't happen normally).
     */
    private suspend fun consumeUntilMatch(
        channel: kotlinx.coroutines.channels.ReceiveChannel<PlayPurchaseEvents.Event>,
        productId: String,
    ): PlayPurchaseEvents.Event? {
        for (event in channel) {
            if (matchesProduct(event, productId)) {
                return event
            }
            logger.debug { "Skipping event for different product" }
        }
        return null
    }

    private suspend fun loadProductDetails(localProduct: Product): ProductDetails? =
        suspendCancellableCoroutine { continuation ->
            if (billingClient.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS).responseCode == BillingClient.BillingResponseCode.OK) {
                val type = when (localProduct.type) {
                    ProductType.SUBSCRIPTION -> BillingClient.ProductType.SUBS
                    else -> BillingClient.ProductType.INAPP
                }
                val productList = listOf(
                    QueryProductDetailsParams.Product.newBuilder().setProductId(localProduct.sku)
                        .setProductType(type).build()
                )
                billingClient.queryProductDetailsAsync(
                    QueryProductDetailsParams.newBuilder().setProductList(productList).build()
                ) { _, productDetailsList ->
                    val productDetails =
                        productDetailsList.productDetailsList.firstOrNull { productDetails ->
                            productDetails.productId == localProduct.sku
                        }
                    continuation.resume(productDetails)
                }
            } else {
                continuation.resume(null)
            }
            continuation.invokeOnCancellation {
                // don't need to cancel the queryProductDetailsAsync call, but we should at least log it
                logger.debug { "loadProductDetails for ${localProduct.sku} was cancelled" }
            }
        }

    private fun matchesProduct(
        event: PlayPurchaseEvents.Event, productId: String
    ): Boolean =
        event.purchases?.any { it.products.contains(productId) } == true || event.result.responseCode != BillingClient.BillingResponseCode.OK

    private fun mapToState(
        event: PlayPurchaseEvents.Event,
        productId: String,
        offerToken: String,
    ): BillingLaunchState {
        return when (val code = event.result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase: Purchase? =
                    event.purchases?.firstOrNull { it.products.contains(productId) }
                if (purchase != null) {
                    BillingLaunchState.Completed(
                        productSku = productId,
                        offerToken = offerToken,
                        purchaseToken = purchase.purchaseToken,
                        orderId = purchase.orderId,
                        purchaseTimestamp = purchase.purchaseTime,
                        verified = purchase.isAcknowledged
                    )
                } else {
                    BillingLaunchState.Failed(
                        productId, offerToken,
                        "OK response but no matching purchase",
                        responseCode = code,
                    )
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> BillingLaunchState.Cancelled(
                productId,
                offerToken
            )

            else -> BillingLaunchState.Failed(
                productId, offerToken,
                "Purchase failed: ${event.result.debugMessage}",
                responseCode = code,
            )
        }
    }

}