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
        val verified: Boolean
    ) : BillingLaunchState()
    data class Cancelled(val productSku: String, val offerToken: String?) : BillingLaunchState()
    data class Failed(
        val productSku: String,
        val offerToken: String?,
        val reason: String,
        val responseCode: Int? = null,
        val cause: Throwable? = null,
    ) : BillingLaunchState()
}