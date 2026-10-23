package io.galva.operation_queue

import io.galva.operation_queue.local.StoredOperation
import kotlinx.coroutines.flow.Flow

/**
 * Delivers a batch. Return [Result.success] when the backend accepted it.
 *
 * Failures are classified by the exception:
 * - [PermanentSendException] — retrying cannot help (4xx, malformed batch); the batch is dropped.
 * - anything else — transient (offline, timeout, 408/429/5xx); the batch is retried with backoff.
 */
fun interface BatchSender {
    suspend fun send(batch: List<StoredOperation>): Result<Unit>
}

/** Signals a batch the backend will never accept, so the queue drops it instead of retrying. */
class PermanentSendException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface NetworkMonitor {
    val isOnline: Flow<Boolean>
}

fun interface Clock {
    fun nowMs(): Long
}

fun interface IdGenerator {
    fun next(): String
}
