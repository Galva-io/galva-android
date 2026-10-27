package io.galva.sdk.impl.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient
import io.galva.billing.BillingManager
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.FullCatalog
import io.galva.billing.model.ProductCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Used when the app ships no Play Billing Library 8.0+: touching Play's classes would throw
 * `NoClassDefFoundError` / `NoSuchMethodError`. The rest of the SDK keeps working; purchases a
 * message requests fail with a purchase error instead of crashing.
 */
internal class UnavailableBillingManager : BillingManager {
    private val launchState = MutableStateFlow<BillingLaunchState?>(null)

    override val catalog: Flow<FullCatalog> = emptyFlow()
    override val latestLaunchState: StateFlow<BillingLaunchState?> = launchState.asStateFlow()

    override fun initialize() = Unit
    override suspend fun loadProducts() = Unit
    override suspend fun getStorefrontCountryCode(): String? = null

    override fun launchBilling(
        activity: Activity,
        productId: String,
        basePlanId: String,
        offerId: String?,
        obfuscatedAccountId: String?,
    ): Flow<BillingLaunchState> = flowOf(
        BillingLaunchState.Failed(
            productId, offerId, REASON,
            // A compile-time constant: reading it loads no Play class.
            responseCode = BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            kind = BillingLaunchState.FailureKind.BILLING,
        ).also { launchState.value = it }
    )

    override suspend fun getProductCatalog(productId: String): ProductCatalog? = null

    private companion object {
        const val REASON = "Play Billing Library 8.0+ is not in the app"
    }
}
