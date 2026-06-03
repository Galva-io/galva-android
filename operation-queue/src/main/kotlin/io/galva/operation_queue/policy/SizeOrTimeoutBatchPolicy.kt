package io.galva.operation_queue.policy

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

class SizeOrTimeoutBatchPolicy(
    override val maxBatchSize: Int,
    private val flushTimeout: Duration,
) : BatchPolicy {
    init {
        require(maxBatchSize  > 0) { "maxBatchSize must be greater than 0" }
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