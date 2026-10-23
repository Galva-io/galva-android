package io.galva.sdk.impl.inappmessage

import com.android.billingclient.api.BillingClient.BillingResponseCode
import io.galva.billing.model.BillingLaunchState
import io.galva.billing.model.BillingLaunchState.FailureKind
import io.galva.common.utils.DateTimeFormatUtils
import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.BridgeException
import io.galva.iam.bridge.PurchaseRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

/**
 * Turns billing states into `requestPurchase` answers with the iOS SDK's contract: `completed`,
 * `pending` and `cancelled` are results; everything else is a [BridgeError] with the matching code.
 */
internal object PurchaseBridgeMapper {

    /** The answer for a terminal [state], or null while the flow is still running. */
    fun map(state: BillingLaunchState, request: PurchaseRequest): Result<JsonObject>? = when (state) {
        is BillingLaunchState.Launching, is BillingLaunchState.Showing -> null
        is BillingLaunchState.Pending -> Result.success(outcome("pending"))
        is BillingLaunchState.Cancelled -> Result.success(outcome("cancelled"))
        is BillingLaunchState.Completed -> Result.success(completed(state, request))
        is BillingLaunchState.Failed -> Result.failure(BridgeException(error(state, request)))
    }

    fun error(state: BillingLaunchState.Failed, request: PurchaseRequest): BridgeError {
        val offerRequested = request.offerId != null
        val code = when (state.kind) {
            FailureKind.PRODUCT_NOT_FOUND -> BridgeError.Code.PRODUCT_UNAVAILABLE
            // Play only lists the offers a user is eligible for.
            FailureKind.OFFER_NOT_FOUND ->
                if (offerRequested) BridgeError.Code.INELIGIBLE_FOR_OFFER else BridgeError.Code.PRODUCT_UNAVAILABLE

            FailureKind.NOT_CONNECTED -> BridgeError.Code.NETWORK_ERROR
            FailureKind.TIMEOUT -> BridgeError.Code.TIMEOUT
            FailureKind.BILLING -> when (state.responseCode) {
                BillingResponseCode.ITEM_UNAVAILABLE -> BridgeError.Code.PRODUCT_UNAVAILABLE
                BillingResponseCode.BILLING_UNAVAILABLE,
                BillingResponseCode.FEATURE_NOT_SUPPORTED -> BridgeError.Code.PURCHASE_NOT_ALLOWED

                BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingResponseCode.NETWORK_ERROR,
                BillingResponseCode.SERVICE_DISCONNECTED -> BridgeError.Code.NETWORK_ERROR

                // Invalid launch arguments; with an offer that is almost always a bad offer token.
                BillingResponseCode.DEVELOPER_ERROR ->
                    if (offerRequested) BridgeError.Code.INVALID_OFFER else BridgeError.Code.PURCHASE_FAILED

                else -> BridgeError.Code.PURCHASE_FAILED
            }
        }
        return BridgeError(code, state.reason)
    }

    private fun outcome(value: String) = buildJsonObject { put("outcome", value) }

    private fun completed(state: BillingLaunchState.Completed, request: PurchaseRequest) = buildJsonObject {
        put("outcome", "completed")
        put("transaction", buildJsonObject {
            put("id", state.orderId)
            put("productId", request.productId)
            put("basePlanId", request.basePlanId)
            put("offerId", request.offerId)
            put(
                "purchaseDate",
                DateTimeFormatUtils.format(Calendar.getInstance().apply { timeInMillis = state.purchaseTimestamp })
            )
            put("verified", state.verified)
            state.obfuscatedAccountId?.let { put("appAccountToken", JsonPrimitive(it)) }
        })
    }
}
