package io.galva.sdk.impl.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import io.galva.billing.BillingLauncher
import io.galva.billing.BillingManager
import io.galva.billing.local.BillingSqliteHelper
import io.galva.billing.local.SqliteCatalogStore
import io.galva.billing.local.SqliteEntitlementStore
import io.galva.billing.model.BillingConnection
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.ProductCatalog
import io.galva.billing.source.ProductIdSource
import io.galva.billing.store.CatalogStore
import io.galva.billing.store.EntitlementStore
import io.galva.common.logger.Logger
import io.galva.common.utils.JsonUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DefaultBillingManager(
    private val connection: BillingConnection,
    private val productIdSource: ProductIdSource,
    private val resolver: ProductDetailsResolver,
    private val purchaseResolver: PurchaseResolver,
    private val mapper: ProductDetailsMapper,
    private val purchaseMapper: PurchaseToEntitlementMapper,
    private val entitlementStore: EntitlementStore,
    private val catalogStore: CatalogStore,
    private val productDetailsCache: ProductDetailsCache,
    private val launcher: BillingLauncher,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val launchDispatcher: CoroutineDispatcher = Dispatchers.Default
) : BillingManager {
    override val catalog: Flow<FullCatalog> = catalogStore.observe()

    private val _latestLaunchState = MutableStateFlow<BillingLaunchState?>(null)
    override val latestLaunchState: StateFlow<BillingLaunchState?> =
        _latestLaunchState.asStateFlow()

    // ── Concurrency ─────────────────────────────────────────────────────────

    private val refreshMutex = Mutex()
    private val launchMutex = Mutex()

    override fun initialize() {
        scope.launch {
            runCatching {
                connection.ensureConnectedWithRetry()
                launch {
                    // load storefront country code on initialization to cache result for later use during purchase flow
                    getStorefrontCountryCode()
                }
                launch {
                    purchaseResolver.loadPurchases().collect {
                        handlePurchaseEvent(it)
                    }
                }
            }.onFailure { logger.warn(it) { "Play Billing unavailable; in-app message purchases will fail on this device" } }
        }
    }

    private suspend fun handlePurchaseEvent(event: PlayPurchaseEvents.Event) {
        if (event.result.responseCode != BillingClient.BillingResponseCode.OK) return

        event.purchases?.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                onPurchaseComplete(purchase)
            }
        }
    }

    private suspend fun onPurchaseComplete(purchase: Purchase) {
        val entitlement = purchaseMapper.map(purchase) ?: return
        entitlementStore.save(entitlement)
    }


    override suspend fun loadProducts() = refreshMutex.withLock {
        try {
            val productIds = productIdSource.fetchProductIds()
            if (productIds.isEmpty()) {
                catalogStore.clear()
                productDetailsCache.clear()
                return@withLock
            }

            val details = resolver.resolve(productIds)
            val missing = productIds - details.map { it.productId }.toSet()
            if (missing.isNotEmpty()) {
                logger.warn {
                    "Products configured in Galva but not found on Google Play: ${missing.joinToString()}. " +
                        "Check they are active in Play Console for this app's package name, and that " +
                        "Play Billing works on this device."
                }
            }
            productDetailsCache.replace(details)

            val catalog = mapper.map(details)
            catalogStore.replaceAll(catalog)

            logger.info { "Catalog refreshed: ${catalog.products.size} of ${productIds.size} products available" }
        } catch (t: Throwable) {
            logger.warn(t) { "Catalog refresh failed; keeping the cached catalog" }
        }
    }

    override suspend fun getStorefrontCountryCode(): String? {
        return launcher.loadStorefrontCountryCode()
    }

    override fun launchBilling(
        activity: Activity,
        productId: String,
        basePlanId: String,
        offerId: String?,
        obfuscatedAccountId: String?,
    ): Flow<BillingLaunchState> = flow {
        launchMutex.withLock {
            // Validate inputs against local DB
            val product = catalogStore.getProduct(productId)
            if (product == null) {
                val state = BillingLaunchState.Failed(
                    productId, offerId, "Product not found",
                    kind = BillingLaunchState.FailureKind.PRODUCT_NOT_FOUND,
                )
                emit(state)
                _latestLaunchState.value = state
                return@withLock
            }

            val offer = catalogStore.getOfferForPlan(basePlanId, offerId)
            if (offer == null) {
                val state = BillingLaunchState.Failed(
                    productId, offerId, "Offer not found",
                    kind = BillingLaunchState.FailureKind.OFFER_NOT_FOUND,
                )
                emit(state); _latestLaunchState.value = state
                return@withLock
            }

            logger.debug { "Launching billing: product=$productId offer=$offerId offerToken: ${offer.offerToken}" }

            launcher.launch(activity, productId, offer.offerToken, obfuscatedAccountId)
                .collect { state ->
                    logger.debug {
                        "Billing flow state: $state for product=$productId offer=$offerId"
                    }
                    emit(state)
                    _latestLaunchState.value = state
                }
        }
    }.flowOn(launchDispatcher)

    override suspend fun getProductCatalog(productId: String): ProductCatalog? {
        return catalogStore.getProductWithOffers(productId)
    }

    /** Tear down billing client + caches; safe to call from Galva.shutdown(). */
    fun shutdown() {
        scope.launch {
            try {
                connection.endConnection()
            } finally {
                productDetailsCache.clear()
                _latestLaunchState.value = null
            }
        }
    }

    companion object {
        fun create(
            context: Context,
            logger: Logger,
            productIdSource: ProductIdSource,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
        ): DefaultBillingManager {
            val purchasesUpdatedListener = PlayPurchaseEvents()
            val billingClientFactory =
                BillingClientFactory(context, purchasesUpdatedListener, logger)
            val billingClient = billingClientFactory.create()
            val productDetailsCache = InMemoryProductDetailsCache()
            val connection = PlayBillingConnection(billingClient, logger)
            val purchaseMapper = PlayBillingPurchaseToEntitlementMapper()
            val helper = BillingSqliteHelper(context)
            val catalogStore = SqliteCatalogStore(helper, logger, JsonUtils.defaultJson)
            val entitlementStore: EntitlementStore = SqliteEntitlementStore(helper, logger)
            val purchaseResolver = PlayPurchaseResolver(purchaseEvents = purchasesUpdatedListener)
            return DefaultBillingManager(
                connection = connection,
                productIdSource = productIdSource,
                resolver = PlayProductDetailsResolver(billingClient, connection, logger),
                mapper = PlayBillingProductDetailsMapper(),
                catalogStore = catalogStore,
                productDetailsCache = productDetailsCache,
                launcher = PlayBillingLauncher(
                    billingClient = billingClient,
                    connection = connection,
                    cache = productDetailsCache,
                    catalogStore = catalogStore,
                    purchaseEvents = purchasesUpdatedListener,
                    logger = logger,
                    entitlementStore = entitlementStore
                ),
                logger = logger,
                scope = scope,
                purchaseMapper = purchaseMapper,
                entitlementStore = entitlementStore,
                purchaseResolver = purchaseResolver
            )
        }
    }
}