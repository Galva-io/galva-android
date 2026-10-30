package io.galva.billing.model

sealed class BillingLaunchState {
    data class Launching(val productSku: String, val offerToken: String?) : BillingLaunchState()
    data class Showing(val productSku: String, val offerToken: String?) : BillingLaunchState()
    data class Completed(
        val productSku: String,
        val offerToken: String?,
        val purchaseToken: String,
        val orderId: String?,
        val purchaseTimestamp:Long,
        val verified: Boolean,
        /** `obfuscatedAccountId` Play recorded on the purchase, if any. */
        val obfuscatedAccountId: String? = null,
    ) : BillingLaunchState()

    /** Payment not finished yet (pending payment method / approval); Play delivers it later. */
    data class Pending(val productSku: String, val offerToken: String?) : BillingLaunchState()
    data class Cancelled(val productSku: String, val offerToken: String?) : BillingLaunchState()
    data class Failed(
        val productSku: String,
        val offerToken: String?,
        val reason: String,
        val responseCode: Int? = null,
        val cause: Throwable? = null,
        val kind: FailureKind = FailureKind.BILLING,
    ) : BillingLaunchState()

    enum class FailureKind {
        /** The product is not in the local catalog / unknown to Play. */
        PRODUCT_NOT_FOUND,

        /** The base plan or offer is not in the catalog; Play only lists offers the user is eligible for. */
        OFFER_NOT_FOUND,

        /** The Play Billing service could not be reached. */
        NOT_CONNECTED,

        /** No purchase result arrived in time. */
        TIMEOUT,

        /** Play returned an error; see [Failed.responseCode]. */
        BILLING,
    }
}
