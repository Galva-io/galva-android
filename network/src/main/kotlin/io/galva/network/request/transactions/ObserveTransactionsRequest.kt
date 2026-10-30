package io.galva.network.request.transactions

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.Serializable

/**
 * Body of `POST /v1/transactions/observe`: tells Galva which user this device's Play purchases
 * belong to, so store notifications for them can be joined to that user.
 */
@Serializable
@InternalGalvaApi
data class ObserveTransactionsRequest(
    val anonymousId: String,
    val endUserId: String?,
    val transactions: List<ObservedTransaction>,
)

@Serializable
@InternalGalvaApi
data class ObservedTransaction(
    val orderId: String?,
    val purchaseToken: String,
)
