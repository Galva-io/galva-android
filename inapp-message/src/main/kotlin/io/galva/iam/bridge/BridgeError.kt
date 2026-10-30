package io.galva.iam.bridge

import io.galva.common.InternalGalvaApi
import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured error returned to the bundle as `{ requestId, error: { code, message } }` so its
 * pending Promise rejects instead of hanging. Codes match the iOS SDK's `BridgeError.Code`; the
 * code is the contract, the message is for diagnostics only.
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
@InternalGalvaApi
data class BridgeError(val code: Code, val message: String) {

    @Serializable
    enum class Code {
        /** The bundle called a method the SDK doesn't know. */
        @SerialName("unknownMethod") UNKNOWN_METHOD,

        /** No message / screen is available to serve the call (e.g. an alert closed without an action). */
        @SerialName("noActiveMessage") NO_ACTIVE_MESSAGE,

        /** The product is unknown to Google Play or missing from the catalog. */
        @SerialName("productUnavailable") PRODUCT_UNAVAILABLE,

        /** The resolved message payload is not available. */
        @SerialName("messageDataUnavailable") MESSAGE_DATA_UNAVAILABLE,

        /** Missing or malformed payload fields. */
        @SerialName("invalidPayload") INVALID_PAYLOAD,

        /** No result arrived in time. */
        @SerialName("timeout") TIMEOUT,

        /** Generic billing failure; see the message. */
        @SerialName("purchaseFailed") PURCHASE_FAILED,

        /** No app on the device can open the URL. */
        @SerialName("urlOpenFailed") URL_OPEN_FAILED,

        /** apiFetch could not complete the round-trip (transport failure). HTTP errors are not failures. */
        @SerialName("apiRequestFailed") API_REQUEST_FAILED,

        /** Purchases are unavailable on this device / account. */
        @SerialName("purchaseNotAllowed") PURCHASE_NOT_ALLOWED,

        /** The user is not eligible for the offer. */
        @SerialName("ineligibleForOffer") INELIGIBLE_FOR_OFFER,

        /** Google Play rejected the offer parameters. */
        @SerialName("invalidOffer") INVALID_OFFER,

        /** The purchase could not be verified. */
        @SerialName("verificationFailed") VERIFICATION_FAILED,

        /** Google Play could not be reached. */
        @SerialName("networkError") NETWORK_ERROR,

        /** The product is not sold in the user's country. */
        @SerialName("notAvailableInStorefront") NOT_AVAILABLE_IN_STOREFRONT,
    }
}

/** Thrown by the payload parsers; the bridge answers with [error]. */
@InternalGalvaApi
class BridgeException(val error: BridgeError) : Exception(error.message)

internal fun invalidPayload(message: String) =
    BridgeException(BridgeError(BridgeError.Code.INVALID_PAYLOAD, message))
