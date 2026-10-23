package io.galva.operation_queue.networkgate

import io.galva.common.InternalGalvaApi

/** Decides when network state allows sending. */
@InternalGalvaApi
interface NetworkGate {
    suspend fun awaitOnline();
    suspend fun isOnline(): Boolean
}