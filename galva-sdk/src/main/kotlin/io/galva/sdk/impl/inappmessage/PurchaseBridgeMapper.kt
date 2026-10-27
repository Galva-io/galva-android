package io.galva.sdk.impl.inappmessage

import io.galva.common.utils.DateTimeFormatUtils
import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.PurchaseRequest
import io.galva.sdk.GalvaPurchaseException
import io.galva.sdk.GalvaPurchaseResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

/**
 * Turns purchase outcomes into `requestPurchase` answers with the iOS SDK's contract: results for
 * `completed`, `pending` and `cancelled`, a [BridgeError] with the matching code for failures. The
 * answer has the same shape whether the built-in Play flow or a purchase controller ran it.
 */
internal object PurchaseBridgeMapper {

    fun result(result: GalvaPurchaseResult, request: PurchaseRequest): JsonObject = when (result) {
        GalvaPurchaseResult.Pending -> outcome("pending")
        GalvaPurchaseResult.Cancelled -> outcome("cancelled")
        is GalvaPurchaseResult.Completed -> completed(result, request)
    }

    fun error(failure: GalvaPurchaseException): BridgeError {
        val code = when (failure) {
            is GalvaPurchaseException.ProductUnavailable -> BridgeError.Code.PRODUCT_UNAVAILABLE
            is GalvaPurchaseException.PurchaseNotAllowed -> BridgeError.Code.PURCHASE_NOT_ALLOWED
            is GalvaPurchaseException.IneligibleForOffer -> BridgeError.Code.INELIGIBLE_FOR_OFFER
            is GalvaPurchaseException.NotAvailableInStorefront -> BridgeError.Code.NOT_AVAILABLE_IN_STOREFRONT
            is GalvaPurchaseException.InvalidOffer -> BridgeError.Code.INVALID_OFFER
            is GalvaPurchaseException.VerificationFailed -> BridgeError.Code.VERIFICATION_FAILED
            is GalvaPurchaseException.NetworkError -> BridgeError.Code.NETWORK_ERROR
            is GalvaPurchaseException.Timeout -> BridgeError.Code.TIMEOUT
            is GalvaPurchaseException.Failed -> BridgeError.Code.PURCHASE_FAILED
        }
        return BridgeError(code, failure.message.orEmpty())
    }

    private fun outcome(value: String) = buildJsonObject { put("outcome", value) }

    private fun completed(result: GalvaPurchaseResult.Completed, request: PurchaseRequest) = buildJsonObject {
        val transaction = result.transaction
        put("outcome", "completed")
        put("transaction", buildJsonObject {
            put("id", transaction.orderId)
            put("productId", request.productId)
            put("basePlanId", request.basePlanId)
            put("offerId", request.offerId)
            put(
                "purchaseDate",
                DateTimeFormatUtils.format(Calendar.getInstance().apply { timeInMillis = transaction.purchaseTime })
            )
            put("verified", transaction.acknowledged)
            transaction.obfuscatedAccountId?.let { put("appAccountToken", JsonPrimitive(it)) }
        })
    }
}
