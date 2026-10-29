package io.galva.operation_queue

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface OperationQueue {
    fun start()

    /**
     * Persists an operation. [payload] is complete when enqueued (identity included), so it is
     * sent as is, whichever identity is current by then.
     */
    suspend fun enqueue(payload: String, type: String, id: String? = null)
    fun flush()
    fun stop()

    suspend fun clear()
}
