package io.galva.operation_queue.policy

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SizeOrTimeoutBatchPolicy(
    initialMaxBatchSize: Int = 10,
    initialFlushTimeout: Duration = 30.seconds,
) : BatchPolicy {
    // Atomic refs for thread-safe updates without locking
    private val _maxBatchSize = AtomicInteger(initialMaxBatchSize)
    private val _flushTimeout = AtomicReference(initialFlushTimeout)

    override val maxBatchSize: Int get() = _maxBatchSize.get()
    override val flushTimeout: Duration get() = _flushTimeout.get()
    init {
        require(maxBatchSize  > 0) { "maxBatchSize must be greater than 0" }
    }

    override fun update(maxBatchSize: Int?, flushTimeout: Duration?) {
        require(maxBatchSize == null || maxBatchSize > 0) {
            "maxBatchSize must be positive, got $maxBatchSize"
        }
        require(flushTimeout == null || flushTimeout.isPositive()) {
            "flushTimeout must be positive, got $flushTimeout"
        }

        maxBatchSize?.let { _maxBatchSize.set(it) }
        flushTimeout?.let { _flushTimeout.set(it) }
    }
    override suspend fun awaitFlush(
        pendingCount: suspend () -> Int,
        awaitEnqueue: suspend () -> Unit,
        awaitForceSignal: suspend () -> Unit,
    ) {
        if (pendingCount() >= maxBatchSize) return       // immediate: already at cap
        withTimeoutOrNull(flushTimeout) {           // timeout race
            coroutineScope {
                val force = launch {
                    awaitForceSignal()
                }
                val cap = launch {
                    while (pendingCount() < maxBatchSize) {
                        awaitEnqueue()
                    }
                }
                select<Unit> {
                    force.onJoin {
                        cap.cancel()
                    }
                    cap.onJoin {
                        force.cancel()
                    }
                }
            }
        }
    }
}