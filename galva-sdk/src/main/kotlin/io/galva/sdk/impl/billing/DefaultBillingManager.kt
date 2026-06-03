package io.galva.sdk.impl.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import io.galva.billing.BillingLauncher
import io.galva.billing.BillingManager
import io.galva.billing.local.BillingSqliteHelper
import io.galva.billing.local.SqliteCatalogStore
import io.galva.billing.model.BillingConnection
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.ProductCatalog
import io.galva.billing.source.ProductIdSource
import io.galva.billing.store.CatalogStore
import io.galva.common.logger.Logger
import io.galva.common.utils.JsonUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultBillingManager(
    private val connection: BillingConnection,
    private val productIdSource: ProductIdSource,
    private val resolver: ProductDetailsResolver,
    private val mapper: ProductDetailsMapper,
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
                    loadProducts()
                }
                launch {
                    // load storefront country code on initialization to cache result for later use during purchase flow
                    getStorefrontCountryCode()
                }
            }.onFailure { logger.error(it) { "Warm-up connection failed" } }
        }
    }

    override suspend fun loadProducts() = refreshMutex.withLock {
        try {
            val productIds = productIdSource.fetchProductIds()
            logger.debug { "Fetched ${productIds.size} product ids" }
            if (productIds.isEmpty()) {
                catalogStore.clear()
                productDetailsCache.clear()
                return@withLock
            }

            val details = resolver.resolve(productIds)
            logger.debug { "Resolved ${details.size} ProductDetails from Play" }
            productDetailsCache.replace(details)

            val catalog = mapper.map(details)
            catalogStore.replaceAll(catalog)

            logger.info { "Catalog refreshed: ${catalog.products.size} products" }
        } catch (t: Throwable) {
            logger.error(t) { "Catalog refresh failed" }
        }
    }

    override suspend fun getStorefrontCountryCode(): String? {
        return launcher.loadStorefrontCountryCode()
    }

    override fun launchBilling(
        activity: Activity,
        productId: String,
        basePlanId:String,
        offerId: String,
        obfuscatedAccountId: String?,
    ): Flow<BillingLaunchState> = flow {
        launchMutex.withLock {
            // Validate inputs against local DB
            val product = catalogStore.getProduct(productId)
            if (product == null) {
                val state = BillingLaunchState.Failed(productId, offerId, "Product not found")
                emit(state)
                _latestLaunchState.value = state
                return@withLock
            }

            val offer = catalogStore.getOfferForPlan(basePlanId,offerId)
            if (offer == null) {
                val state = BillingLaunchState.Failed(productId, offerId, "Offer not found")
                emit(state); _latestLaunchState.value = state
                return@withLock
            }

            logger.debug { "Launching billing: product=$productId offer=$offerId offerToken: ${offer.offerToken}" }

            launcher.launch(activity, productId, offer.offerToken, obfuscatedAccountId).collect { state ->
                logger.debug {
                    "Billing flow state: $state for product=$productId offer=$offerId"
                }
                emit(state)
                _latestLaunchState.value = state
            }
        }
    }.flowOn(launchDispatcher)

    override fun getProductCatalog(productId: String): Flow<ProductCatalog?> {
        return catalogStore.observeProduct(productId)
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
            val billingClientFactory = BillingClientFactory(context,purchasesUpdatedListener,logger)
            val billingClient = billingClientFactory.create()
            val productDetailsCache = InMemoryProductDetailsCache()
            val connection = PlayBillingConnection(billingClient, logger)
            val helper = BillingSqliteHelper(context)
            val catalogStore = SqliteCatalogStore(helper,logger, JsonUtils.defaultJson)
            return DefaultBillingManager(
                connection = PlayBillingConnection(billingClient, logger),
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
                ),
                logger = logger,
                scope = scope
            )
        }
    }
}