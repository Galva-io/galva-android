package io.galva.operation_queue.policy

import io.galva.common.InternalGalvaApi
import kotlin.time.Duration

/** Decides WHEN to flush. Implementations: SizeOrTimeout, SizeOnly, TimeoutOnly, Adaptive… */
@InternalGalvaApi
interface BatchPolicy {
    val maxBatchSize: Int
    val flushTimeout: Duration

    /**
     * Update batch policy values. Takes effect on the next flush trigger;
     * an in-flight wait completes with the OLD timeout, then the next
     * iteration uses the new values.
     */
    fun update(maxBatchSize: Int? = null, flushTimeout: Duration? = null)

    /** Suspends until the next flush should happen, given current pending count. */
    suspend fun awaitFlush(
        pendingCount: suspend () -> Int,
        awaitEnqueue: suspend () -> Unit,
        awaitForceSignal: suspend () -> Unit,
    )
}