package io.galva.sdk.impl.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import io.galva.billing.BillingLauncher
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.Product
import io.galva.billing.model.ProductType
import io.galva.billing.store.CatalogStore
import io.galva.billing.store.EntitlementStore
import io.galva.common.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class PlayBillingLauncher(
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
    /**
     * The Play storefront country, or null when Play can't tell quickly. Only a hint (callers fall
     * back to the device locale), so it never throws and never waits long: a device without Play
     * would otherwise hold up every message.
     */
    override suspend fun loadStorefrontCountryCode(): String? {
        storefrontCountryCode?.let { return it }
        return try {
            withTimeoutOrNull(STOREFRONT_TIMEOUT) {
                connection.ensureConnectedWithRetry()
                queryStorefrontCountryCode()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug { "Storefront country unavailable: ${e.message}" }
            null
        }
    }

    private suspend fun queryStorefrontCountryCode(): String? {
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
                    kind = BillingLaunchState.FailureKind.NOT_CONNECTED,
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
                        kind = BillingLaunchState.FailureKind.PRODUCT_NOT_FOUND,
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
            val replacement = if (productDetails.productType == BillingClient.ProductType.SUBS) {
                subscriptionReplacement(obfuscatedAccountId)
            } else {
                SubscriptionReplacement(oldPurchaseToken = null, obfuscatedAccountId = obfuscatedAccountId)
            }
            val flowParams =
                BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams))
                    .apply {
                        replacement.oldPurchaseToken?.let { oldPurchaseToken ->
                            logger.debug { "Replacing the owned subscription with $productId" }
                            setSubscriptionUpdateParams(
                                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                    .setOldPurchaseToken(oldPurchaseToken)
                                    .build()
                            )
                        }
                        replacement.obfuscatedAccountId?.let { setObfuscatedAccountId(it) }
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
                            kind = BillingLaunchState.FailureKind.TIMEOUT,
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
                    kind = BillingLaunchState.FailureKind.PRODUCT_NOT_FOUND,
                )
            )
        }


    }

    /** How a subscription purchase replaces the account's owned subscription; see [SubscriptionReplacement.select]. */
    private suspend fun subscriptionReplacement(accountId: String?): SubscriptionReplacement {
        queryOwnedSubscriptions()?.let { return SubscriptionReplacement.select(it, accountId) }
        // Play didn't answer: replace the subscription Galva's client last saw bought, whose
        // account id is unknown, so none is attached.
        val entitlement = entitlementStore.get()
            ?.takeIf { cache.get(it.productId)?.productType == BillingClient.ProductType.SUBS }
        return SubscriptionReplacement(
            oldPurchaseToken = entitlement?.purchaseToken,
            obfuscatedAccountId = if (entitlement == null) accountId else null,
        )
    }

    /** The account's purchased (not pending) subscriptions, or null when Play can't tell. */
    private suspend fun queryOwnedSubscriptions(): List<OwnedSubscription>? = try {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val result = billingClient.queryPurchasesAsync(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            result.purchasesList
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .map { it.toOwnedSubscription() }
        } else {
            logger.debug { "queryPurchases(subs) failed: ${result.billingResult.responseCode}" }
            null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug(e) { "queryPurchases(subs) failed" }
        null
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
                if (purchase?.purchaseState == Purchase.PurchaseState.PENDING) {
                    BillingLaunchState.Pending(productId, offerToken)
                } else if (purchase != null) {
                    BillingLaunchState.Completed(
                        productSku = productId,
                        offerToken = offerToken,
                        purchaseToken = purchase.purchaseToken,
                        orderId = purchase.orderId,
                        purchaseTimestamp = purchase.purchaseTime,
                        verified = purchase.isAcknowledged,
                        obfuscatedAccountId = purchase.accountIdentifiers?.obfuscatedAccountId,
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

    private companion object {
        val STOREFRONT_TIMEOUT = 5.seconds
    }
}
