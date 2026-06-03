package io.galva.operation_queue.networkgate

/** Decides when network state allows sending. */
interface NetworkGate {
    suspend fun awaitOnline();
    suspend fun isOnline(): Boolean
}