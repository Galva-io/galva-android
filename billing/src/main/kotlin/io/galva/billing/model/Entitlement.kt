package io.galva.billing.model

/**
 * Represents the user's currently active subscription entitlement.
 * Used as the basis for upgrades, downgrades, and cross-grade flows.
 */
data class Entitlement(
    val productId: String,
    val purchaseToken: String,
    val orderId: String?,
    val acquiredAtMs: Long,
    val isAutoRenewing: Boolean,
    val state: EntitlementState,
)

enum class EntitlementState {
    ACTIVE,
    IN_GRACE_PERIOD,
    ON_HOLD,
    PAUSED,
    EXPIRED,
}