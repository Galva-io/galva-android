package io.galva.sdk.impl.billing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class PlayPurchaseResolver(
    private val purchaseEvents: PlayPurchaseEvents,
) : PurchaseResolver {
    override fun loadPurchases(): Flow<PlayPurchaseEvents.Event> {
        val eventChannel = purchaseEvents.subscribe()
        return eventChannel.receiveAsFlow()
    }
}