package io.galva.operation_queue.policy

/** Decides WHEN to flush. Implementations: SizeOrTimeout, SizeOnly, TimeoutOnly, Adaptive… */
interface BatchPolicy {
    val maxBatchSize: Int
    /** Suspends until the next flush should happen, given current pending count. */
    suspend fun awaitFlush(
        pendingCount: suspend () -> Int,
        awaitEnqueue: suspend () -> Unit,
        awaitForceSignal: suspend () -> Unit,
    )
}