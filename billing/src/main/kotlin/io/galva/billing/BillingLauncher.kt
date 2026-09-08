package io.galva.billing

import android.app.Activity
import io.galva.billing.model.BillingLaunchState
import kotlinx.coroutines.flow.Flow

interface BillingLauncher {

   suspend fun loadStorefrontCountryCode(): String?

    /**
     * Launch the platform billing flow.
     * @param obfuscatedAccountId optional account hint for the platform (Play attribution).
     * @return final state after the flow resolves.
     */
     fun launch(
        activity: Activity,
        productId: String,
        offerToken:String,
        obfuscatedAccountId: String? = null,
    ): Flow<BillingLaunchState>
}