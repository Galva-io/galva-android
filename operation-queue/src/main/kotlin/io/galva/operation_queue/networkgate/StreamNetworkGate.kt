package io.galva.operation_queue.networkgate

import io.galva.operation_queue.NetworkMonitor
import kotlinx.coroutines.flow.first

class StreamNetworkGate(private val monitor: NetworkMonitor) : NetworkGate {
    override suspend fun isOnline() = monitor.isOnline.first()
    override suspend fun awaitOnline() {
        monitor.isOnline.first { it }
    }
}