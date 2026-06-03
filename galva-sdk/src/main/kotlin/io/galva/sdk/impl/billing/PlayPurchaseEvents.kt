package io.galva.sdk.impl.billing

import android.util.Log
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

class PlayPurchaseEvents: PurchasesUpdatedListener {

    data class Event(val result: BillingResult, val purchases: List<Purchase>?)
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<Channel<Event>>()

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val event = Event(result, purchases)
        subscribers.forEach { it.trySend(event) }
    }
    /** Returns a channel that receives every purchase event. Caller closes on completion. */
    fun subscribe(): Channel<Event> {
        val channel = Channel<Event>(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        subscribers.add(channel)
        channel.invokeOnClose {
            subscribers.remove(channel)
        }
        return channel
    }
}