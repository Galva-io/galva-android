package io.galva.sdk.impl.billing

import android.app.Activity
import io.galva.billing.BillingManager
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.ProductCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The [BillingManager] apps get from `Galva.instance.billingManager`. The real manager is built off
 * the main thread after `configure` (creating the Play BillingClient is expensive), so this facade is
 * usable immediately and forwards every call once [delegate] is ready.
 */
internal class DeferredBillingManager(
    private val delegate: Deferred<BillingManager>,
    private val scope: CoroutineScope,
) : BillingManager {

    private val launchState = MutableStateFlow<BillingLaunchState?>(null)

    init {
        scope.launch { delegate.await().latestLaunchState.collect { launchState.value = it } }
    }

    override val catalog: Flow<FullCatalog> = flow { emitAll(delegate.await().catalog) }

    override val latestLaunchState: StateFlow<BillingLaunchState?> = launchState.asStateFlow()

    override fun initialize() {
        scope.launch { delegate.await().initialize() }
    }

    override suspend fun loadProducts() = delegate.await().loadProducts()

    override suspend fun getStorefrontCountryCode(): String? = delegate.await().getStorefrontCountryCode()

    override fun launchBilling(
        activity: Activity,
        productId: String,
        basePlanId: String,
        offerId: String?,
        obfuscatedAccountId: String?,
    ): Flow<BillingLaunchState> = flow {
        emitAll(delegate.await().launchBilling(activity, productId, basePlanId, offerId, obfuscatedAccountId))
    }

    override suspend fun getProductCatalog(productId: String): ProductCatalog? =
        delegate.await().getProductCatalog(productId)
}
