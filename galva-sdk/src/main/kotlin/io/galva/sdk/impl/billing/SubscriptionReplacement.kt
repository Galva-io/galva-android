package io.galva.sdk.impl.billing

import com.android.billingclient.api.Purchase

/** A subscription the Play account owns, reduced to what a plan change needs. */
internal data class OwnedSubscription(
    val purchaseToken: String,
    /** `obfuscatedAccountId` Play recorded on the purchase, if any. */
    val obfuscatedAccountId: String?,
    val purchaseTime: Long,
)

internal fun Purchase.toOwnedSubscription() = OwnedSubscription(
    purchaseToken = purchaseToken,
    obfuscatedAccountId = accountIdentifiers?.obfuscatedAccountId,
    purchaseTime = purchaseTime,
)

/**
 * How a subscription purchase is launched: as a plan change from [oldPurchaseToken], or as a new
 * subscription when it is null, with [obfuscatedAccountId] attached unless it is null.
 */
internal data class SubscriptionReplacement(
    val oldPurchaseToken: String?,
    val obfuscatedAccountId: String?,
) {
    companion object {
        /**
         * Play turns down a plan change whose `obfuscatedAccountId` differs from the replaced
         * purchase's, so the purchase carries the replaced purchase's id:
         * - the newest subscription bought with [accountId] is replaced, keeping [accountId];
         * - otherwise the newest subscription, keeping its own id (none if it had none), since it
         *   may carry the hashed id of an SDK before 1.5.0 or an id from the app's backend;
         * - without a subscription, a new one is bought with [accountId].
         *
         * @param subscriptions the account's purchased (not pending) subscriptions.
         */
        fun select(subscriptions: List<OwnedSubscription>, accountId: String?): SubscriptionReplacement {
            val sameAccount = accountId?.let { id -> subscriptions.filter { it.obfuscatedAccountId == id } }.orEmpty()
            val replaced = sameAccount.maxByOrNull { it.purchaseTime }
                ?: subscriptions.maxByOrNull { it.purchaseTime }
                ?: return SubscriptionReplacement(oldPurchaseToken = null, obfuscatedAccountId = accountId)
            return SubscriptionReplacement(replaced.purchaseToken, replaced.obfuscatedAccountId)
        }
    }
}
