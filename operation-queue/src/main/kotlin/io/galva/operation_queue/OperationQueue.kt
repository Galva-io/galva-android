package io.galva.operation_queue

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface OperationQueue {
    fun start()

    /**
     * Persists an operation. [ownerId] tags it with the identity it belongs to so the
     * operations of one identity can be purged with [clearOwnedBy].
     */
    suspend fun enqueue(payload: String, type: String, id: String? = null, ownerId: String? = null)
    fun flush()
    fun stop()

    suspend fun clear()

    /** Removes every pending operation tagged with [ownerId]; other operations are kept. */
    suspend fun clearOwnedBy(ownerId: String)
}
