package io.galva.operation_queue

import io.galva.operation_queue.local.StoredOperation
import kotlinx.coroutines.flow.Flow

fun interface BatchSender {
    suspend fun send(batch: List<StoredOperation>): Result<Unit>
}

interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

fun interface Clock {
    fun nowMs(): Long
}

fun interface IdGenerator {
    fun next(): String
}
