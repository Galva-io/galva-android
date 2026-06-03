package io.galva.billing

import android.app.Activity
import io.galva.billing.model.BasePlan
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.Offer
import io.galva.billing.model.PricingPhase
import io.galva.billing.model.Product
import io.galva.billing.model.ProductCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BillingManager {
    val catalog: Flow<FullCatalog>

    /** Latest emitted state from the most recent launch, or null before any launch. */
    val latestLaunchState: StateFlow<BillingLaunchState?>
    fun initialize()
    suspend fun loadProducts()
    suspend fun getStorefrontCountryCode(): String?

    /**
     * Launch the billing flow. Returns a Flow emitting each state in the lifecycle.
     * Callers can collect the full progression or just the terminal state.
     */
    fun launchBilling(
        activity: Activity,
        productId: String,
        basePlanId:String,
        offerId: String,
        obfuscatedAccountId: String? = null,
    ): Flow<BillingLaunchState>

    fun getProductCatalog(productId: String): Flow<ProductCatalog?>
}