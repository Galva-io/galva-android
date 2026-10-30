package io.galva.sdk.impl.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.BillingLaunchState.FailureKind
import io.galva.sdk.GalvaPurchaseException
import io.galva.sdk.GalvaPurchaseResult
import io.galva.sdk.GalvaTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull

/**
 * Galva's built-in purchase: the Play Billing flow's states, reported the way a
 * [io.galva.sdk.GalvaPurchaseController] reports a purchase. Same error contract as the iOS SDK:
 * `completed`, `pending` and `cancelled` are results; everything else is a [GalvaPurchaseException].
 */
internal object PlayBillingPurchase {

    /** Waits for the flow's terminal state. */
    suspend fun await(states: Flow<BillingLaunchState>, offerRequested: Boolean): GalvaPurchaseResult =
        states.mapNotNull { result(it, offerRequested) }.firstOrNull()
            ?: throw GalvaPurchaseException.Failed("The billing flow ended without a result")

    /** The result for a terminal [state], or null while the flow is still running. */
    fun result(state: BillingLaunchState, offerRequested: Boolean): GalvaPurchaseResult? = when (state) {
        is BillingLaunchState.Launching, is BillingLaunchState.Showing -> null
        is BillingLaunchState.Pending -> GalvaPurchaseResult.Pending
        is BillingLaunchState.Cancelled -> GalvaPurchaseResult.Cancelled
        is BillingLaunchState.Completed -> GalvaPurchaseResult.Completed(
            GalvaTransaction(
                orderId = state.orderId,
                productId = state.productSku,
                purchaseToken = state.purchaseToken,
                purchaseTime = state.purchaseTimestamp,
                obfuscatedAccountId = state.obfuscatedAccountId,
                acknowledged = state.verified,
            )
        )
        is BillingLaunchState.Failed -> throw exception(state, offerRequested)
    }

    fun exception(state: BillingLaunchState.Failed, offerRequested: Boolean): GalvaPurchaseException {
        val reason = state.reason
        return when (state.kind) {
            FailureKind.PRODUCT_NOT_FOUND -> GalvaPurchaseException.ProductUnavailable(reason)
            // Play only lists the offers a user is eligible for.
            FailureKind.OFFER_NOT_FOUND ->
                if (offerRequested) GalvaPurchaseException.IneligibleForOffer(reason)
                else GalvaPurchaseException.ProductUnavailable(reason)

            FailureKind.NOT_CONNECTED -> GalvaPurchaseException.NetworkError(reason)
            FailureKind.TIMEOUT -> GalvaPurchaseException.Timeout(reason)
            FailureKind.BILLING -> when (state.responseCode) {
                BillingResponseCode.ITEM_UNAVAILABLE -> GalvaPurchaseException.ProductUnavailable(reason)
                BillingResponseCode.BILLING_UNAVAILABLE,
                BillingResponseCode.FEATURE_NOT_SUPPORTED -> GalvaPurchaseException.PurchaseNotAllowed(reason)

                BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingResponseCode.NETWORK_ERROR,
                BillingResponseCode.SERVICE_DISCONNECTED -> GalvaPurchaseException.NetworkError(reason)

                // Invalid launch arguments; with an offer that is almost always a bad offer token.
                BillingResponseCode.DEVELOPER_ERROR ->
                    if (offerRequested) GalvaPurchaseException.InvalidOffer(reason)
                    else GalvaPurchaseException.Failed(reason)

                else -> GalvaPurchaseException.Failed(reason)
            }
        }
    }
}
