package io.galva.operation_queue

import io.galva.common.InternalGalvaApi


import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.operation_queue.local.StoredOperation
import io.galva.operation_queue.networkgate.NetworkGate
import io.galva.operation_queue.policy.BatchPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@InternalGalvaApi
class DefaultOperationQueue(
    private val store: OperationStore,
    private val sender: BatchSender,
    private val gate: NetworkGate,
    private val batchPolicy: BatchPolicy,
    private val retryPolicy: io.galva.common.network.RetryPolicy,
    private val clock: Clock,
    private val idGen: IdGenerator,
    private val transitionTrigger: NetworkTransitionTrigger,   // OFF→ON signal source
    private val scope: CoroutineScope,
    private val logger: Logger = NoOpLogger,
    /** When set, the oldest operations are dropped so the store never holds more than this many. */
    private val maxStoredCount: Int? = null,
    private val enqueueWake: Channel<Unit> = Channel(Channel.CONFLATED),
    private val forceWake: Channel<Unit> = Channel(Channel.CONFLATED),
) : OperationQueue {
    private val lockToken = "queue-${idGen.next()}"
    private var consumerJob: Job? = null
    private val children = mutableListOf<Job>()

    override fun start() {
        logger.info {
            "starting operation queue, releasing locks and launching consumer job"
        }
        if (consumerJob != null) return
        consumerJob = scope.launch {
            store.releaseAllLocks()
            consumeLoop()
        }
        children += scope.launch { transitionTrigger.signals().collect { forceWake.trySend(Unit) } }
    }

    override suspend fun enqueue(payload: String, type: String, id: String?, ownerId: String?) {
        logger.verbose {
            "enqueue: adding operation to store (id=${id ?: "gen"}, type=$type) payload =${payload}"
        }
        store.insert(
            StoredOperation(
                id = id ?: idGen.next(),
                type = type,
                payload = payload,
                createdAt = clock.nowMs(),
                retryCount = 0,
                ownerId = ownerId,
            )
        )
        maxStoredCount?.let { cap ->
            val dropped = store.trimToNewest(cap)
            if (dropped > 0) logger.warn { "enqueue: store cap $cap exceeded, dropped $dropped oldest operations" }
        }
        enqueueWake.trySend(Unit)
    }

    override fun flush() {
        forceWake.trySend(Unit)
    }

    override suspend fun clear() {
        // Rows claimed by an in-flight send are deleted too; the consumer's later
        // delete/release of those ids is then a no-op, so no restart is needed.
        store.clearAll()
    }

    override suspend fun clearOwnedBy(ownerId: String) {
        store.clearOwnedBy(ownerId)
    }

    override fun stop() {
        logger.info {
            "stopping operation queue, cancelling consumer job and children"
        }
        consumerJob?.cancel()
        children.forEach { it.cancel() }
        consumerJob = null
    }

    private suspend fun consumeLoop() {
        while (currentCoroutineContext().isActive) {
            logger.debug {
                "process queue: starting"
            }
            batchPolicy.awaitFlush(
                pendingCount = {
                    store.pendingCount().first()
                },
                awaitForceSignal = {
                    forceWake.receive()
                },
                awaitEnqueue = {
                    enqueueWake.receive()
                },
            )

            if (!gate.isOnline()) {
                gate.awaitOnline()
                continue
            }
            sendNextBatch()
        }
    }

    /**
     * Sends the oldest pending batch. Transient failures are retried with backoff until delivered,
     * re-claiming the batch each time so retry counts are current; permanent failures are dropped
     * so they never block the operations behind them.
     */
    private suspend fun sendNextBatch() {
        while (currentCoroutineContext().isActive) {
            val batch = store.claimBatch(batchPolicy.maxBatchSize, lockToken, clock.nowMs())
            if (batch.isEmpty()) return
            logger.debug {
                "process queue: sending ${batch.size} operations"
            }
            val ids = batch.map { it.id }
            val error = sender.send(batch).exceptionOrNull()
            when {
                error == null -> {
                    logger.debug { "process queue: batch sent successfully, deleting from store" }
                    store.delete(ids)
                    return
                }

                error is PermanentSendException -> {
                    logger.error(error) {
                        "process queue: backend rejected batch permanently, dropping ${batch.size} operations"
                    }
                    store.delete(ids)
                    return
                }

                else -> {
                    store.release(ids)                               // unlock + bump retry_count
                    logger.warn(error) {
                        "process queue: failed to send batch, retrying after backoff"
                    }
                    retryPolicy.backoff(batch.maxOf { it.retryCount } + 1, error)
                    if (!gate.isOnline()) return                     // consumeLoop waits for connectivity
                }
            }
        }
    }
}
