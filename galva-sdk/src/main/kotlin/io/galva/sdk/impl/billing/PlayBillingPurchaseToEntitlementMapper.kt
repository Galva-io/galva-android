package io.galva.sdk.impl.billing

import com.android.billingclient.api.Purchase
import io.galva.billing.model.Entitlement
import io.galva.billing.model.EntitlementState

interface PurchaseToEntitlementMapper{
    /**
     * Map a Play Billing [Purchase] to an [Entitlement].
     *
     */
    fun map(purchase: Purchase): Entitlement?
}
class PlayBillingPurchaseToEntitlementMapper: PurchaseToEntitlementMapper {
    override fun map(
        purchase: Purchase,
    ): Entitlement? {
        // A Purchase carries a list of products (usually 1; multi-item bundles are rare)
        val productId = purchase.products.firstOrNull() ?: return null

        return Entitlement(
            productId = productId,
            purchaseToken = purchase.purchaseToken,
            orderId = purchase.orderId,                                 // may be null for promo codes
            acquiredAtMs = purchase.purchaseTime,
            isAutoRenewing = purchase.isAutoRenewing,
            state = mapState(purchase),
        )
    }
    private fun mapState(purchase: Purchase): EntitlementState = when (purchase.purchaseState) {
        Purchase.PurchaseState.PURCHASED -> {
            if (purchase.isAutoRenewing) EntitlementState.ACTIVE
            else EntitlementState.EXPIRED                               // cancelled but still valid through period
        }
        Purchase.PurchaseState.PENDING -> EntitlementState.ACTIVE       // treat as active during pending
        Purchase.PurchaseState.UNSPECIFIED_STATE -> EntitlementState.EXPIRED
        else -> EntitlementState.EXPIRED
    }

}