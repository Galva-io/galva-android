package io.galva.sdk.impl.billing

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import io.galva.billing.BillingLauncher
import io.galva.billing.model.BillingConnection
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.Offer
import io.galva.billing.model.Product
import io.galva.billing.model.ProductCatalog
import io.galva.billing.source.ProductIdSource
import io.galva.billing.store.CatalogStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

internal class FakeProductIdSource(
    var ids: List<String> = emptyList(),
    var failure: Throwable? = null,
    var delayMs: Long = 0,
) : ProductIdSource {
    var fetchCount = 0
    override suspend fun fetchProductIds(): List<String> {
        fetchCount++
        if (delayMs > 0) delay(delayMs)
        failure?.let { throw it }
        return ids
    }
}

internal class FakeCatalogStore(
    initial: FullCatalog = FullCatalog(emptyList()),
) : CatalogStore {
    private val _state = MutableStateFlow(initial)
    var replaceCalls = 0; private set
    var clearCalls = 0; private set

    override suspend fun load(): FullCatalog = _state.value
    override fun observe(): Flow<FullCatalog> = _state.asStateFlow()
    override fun observeProduct(sku: String): Flow<ProductCatalog?> {
        return _state.mapLatest {
            it.products.find { it.product.sku == sku }
        }
    }
    override suspend fun getProduct(sku: String): Product? =
        _state.value.products.firstOrNull { it.product.sku == sku }?.product

    override suspend fun getOffer(token: String): Offer? =
        _state.value.products.flatMap { it.basePlans }.flatMap { it.offers }
            .firstOrNull { it.offer.offerToken == token }?.offer

    override suspend fun getOfferForPlan(
        basePlanId: String,
        offerId: String
    ): Offer? {
        return _state.value.products.flatMap { it.basePlans }
            .firstOrNull { it.basePlan.id == basePlanId }?.offers
            ?.firstOrNull { it.offer.offerId == offerId }?.offer
    }

    override suspend fun replaceAll(catalog: FullCatalog) {
        replaceCalls++
        _state.value = catalog
    }

    override suspend fun clear() {
        clearCalls++
        _state.value = FullCatalog(emptyList())
    }
}

internal class FakeProductDetailsResolver(
    var result: List<ProductDetails> = emptyList(),         // ← typed now
    var failure: Throwable? = null,
) : ProductDetailsResolver {
    var queries = mutableListOf<List<String>>()
    override suspend fun resolve(productIds: List<String>): List<ProductDetails> {
        queries += productIds
        failure?.let { throw it }
        return result
    }
}

internal class FakeProductDetailsMapper(
    var output: FullCatalog = FullCatalog(emptyList()),
): ProductDetailsMapper {
    override fun map(details: List<ProductDetails>): FullCatalog {
        return output
    }
}

internal class FakeProductDetailsCache : ProductDetailsCache {
    private val map = mutableMapOf<String, ProductDetails>()
    var clearCalls = 0; private set
    var replaceCalls = 0; private set

    override fun get(sku: String): ProductDetails? = map[sku]
    override fun replace(details: List<ProductDetails>) {
        replaceCalls++
        map.clear()
        details.forEach { map[it.productId] = it }
    }

    override fun clear() {
        clearCalls++
        map.clear()
    }
}

internal class FakeBillingLauncher(
    private val states: List<BillingLaunchState>,
    private val emissionGate: CompletableDeferred<Unit>? = null,
) : BillingLauncher {
    val calls = mutableListOf<LaunchCall>()
    override suspend fun loadStorefrontCountryCode(): String? {
        return null
    }

    data class LaunchCall(
        val productId: String,
        val offerToken: String,
        val obfuscatedAccountId: String?,
    )

    override fun launch(
        activity: Activity,
        productId: String,
        offerToken: String,
        obfuscatedAccountId: String?,
    ): Flow<BillingLaunchState> = flow {
        calls += LaunchCall(productId, offerToken, obfuscatedAccountId)
        emissionGate?.await()
        states.forEach {
            emit(it)
        }

    }
    fun release() { emissionGate?.complete(Unit) }
}

internal class FakeBillingConnection(initiallyConnected: Boolean = true) : BillingConnection {
    @Volatile
    private var connected: Boolean = initiallyConnected

    @Volatile
    private var nextResult: Result<Unit> = Result.success(Unit)

    var ensureConnectedCalls = 0
        private set
    var endConnectionCalls = 0
        private set

    /** Suspends ensure-calls until [release] is invoked. */
    private var pendingDeferred: CompletableDeferred<Result<Unit>>? = null

    override val isConnected: Boolean get() = connected

    override suspend fun ensureConnected() {
        ensureConnectedCalls++
        val pending = pendingDeferred
        if (pending != null) {
            val result = pending.await()
            nextResult = result
        }
        val r = nextResult
        if (r.isSuccess) {
            connected = true
        }
        r.getOrThrow()
    }

    override suspend fun ensureConnectedWithRetry() {
        ensureConnected()                                       // same semantics for tests
    }

    override fun endConnection() {
        endConnectionCalls++
        connected = false
    }

    // ── Test helpers ───────────────────────────────────────────────────────

    /** Set the result the next ensure-call will return; immediate (no suspension). */
    fun succeedOnNext() {
        nextResult = Result.success(Unit)
        connected = true
        pendingDeferred?.complete(Result.success(Unit))
        pendingDeferred = null
    }

    fun failOnNext(error: Throwable = IllegalStateException("connection failed")) {
        nextResult = Result.failure(error)
        pendingDeferred?.complete(Result.failure(error))
        pendingDeferred = null
    }

    /** Make the next ensure-call suspend until [release] is called. */
    fun makeEnsureBlock() {
        pendingDeferred = CompletableDeferred()
    }

    fun release(result: Result<Unit> = Result.success(Unit)) {
        nextResult = result
        if (result.isSuccess) connected = true
        pendingDeferred?.complete(result)
        pendingDeferred = null
    }

    /** Simulate a disconnect during operation. */
    fun simulateDisconnect() {
        connected = false
    }

    fun reset() {
        ensureConnectedCalls = 0
        endConnectionCalls = 0
        connected = true
        nextResult = Result.success(Unit)
        pendingDeferred?.cancel()
        pendingDeferred = null
    }
}

fun stubProductDetails(
    sku: String,
    type: String = "subs",
    subscriptionOffers: List<ProductDetails.SubscriptionOfferDetails> = emptyList(),
    oneTimeOffer: ProductDetails.OneTimePurchaseOfferDetails? = null,
): ProductDetails {
    return mock<ProductDetails> {
        on { this.productId } doReturn sku
        on { productType } doReturn type
        on { title } doReturn "Title $sku"
        on { description } doReturn "Desc $sku"
        on { subscriptionOfferDetails } doReturn subscriptionOffers.takeIf { it.isNotEmpty() }
        on { oneTimePurchaseOfferDetails } doReturn oneTimeOffer
    }
}

fun stubSubOffer(
    basePlanId: String,
    offerToken: String,
    offerId: String? = null,
    phases: List<ProductDetails.PricingPhase> = listOf(stubPhase()),
    tags: List<String> = emptyList(),
): ProductDetails.SubscriptionOfferDetails {
    val phasesContainer: ProductDetails.PricingPhases = mock {
        on { pricingPhaseList } doReturn phases
    }
    return mock {
        on { this.basePlanId } doReturn basePlanId
        on { this.offerToken } doReturn offerToken
        on { this.offerId } doReturn offerId
        on { pricingPhases } doReturn phasesContainer
        on { offerTags } doReturn tags
    }
}

fun stubPhase(
    priceMicros: Long = 9_990_000L,
    formatted: String = "$9.99",
    currency: String = "USD",
    period: String = "P1M",
    cycles: Int = 0,
    recurrenceMode: Int = ProductDetails.RecurrenceMode.INFINITE_RECURRING,
): ProductDetails.PricingPhase = mock {
    on { priceAmountMicros } doReturn priceMicros
    on { formattedPrice } doReturn formatted
    on { priceCurrencyCode } doReturn currency
    on { billingPeriod } doReturn period
    on { billingCycleCount } doReturn cycles
    on { this.recurrenceMode } doReturn recurrenceMode
}

fun stubOneTime(
    priceMicros: Long = 990_000L,
    formatted: String = "$0.99",
    currency: String = "USD",
): ProductDetails.OneTimePurchaseOfferDetails = mock {
    on { priceAmountMicros } doReturn priceMicros
    on { formattedPrice } doReturn formatted
    on { priceCurrencyCode } doReturn currency
}