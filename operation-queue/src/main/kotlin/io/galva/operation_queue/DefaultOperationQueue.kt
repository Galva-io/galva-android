package io.galva.operation_queue

import io.galva.common.InternalGalvaApi


import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.operation_queue.local.StoredOperation
import io.galva.operation_queue.networkgate.NetworkGate
import io.galva.operation_queue.policy.BatchPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
        if (consumerJob?.isActive == true) return
        logger.debug { "Operation queue started" }
        consumerJob = scope.launch {
            store.releaseAllLocks()
            consumeLoop()
        }
        children += scope.launch { transitionTrigger.signals().collect { forceWake.trySend(Unit) } }
    }

    override suspend fun enqueue(payload: String, type: String, id: String?) {
        logger.verbose { "Enqueued $type: $payload" }
        store.insert(
            StoredOperation(
                id = id ?: idGen.next(),
                type = type,
                payload = payload,
                createdAt = clock.nowMs(),
                retryCount = 0,
            )
        )
        maxStoredCount?.let { cap ->
            val dropped = store.trimToNewest(cap)
            if (dropped > 0) logger.warn { "Queue holds more than $cap operations; dropped the $dropped oldest" }
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

    override fun stop() {
        if (consumerJob != null) logger.debug { "Operation queue paused" }
        consumerJob?.cancel()
        children.forEach { it.cancel() }
        consumerJob = null
    }

    private suspend fun consumeLoop() {
        while (currentCoroutineContext().isActive) {
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
            try {
                sendNextBatch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. a disk error: keep the queue alive and retry later instead of stopping it.
                logger.error(e) { "Operation queue error; retrying in ${STORE_ERROR_DELAY_MS / 1000} s" }
                delay(STORE_ERROR_DELAY_MS)
            }
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
            val ids = batch.map { it.id }
            val error = sender.send(batch).exceptionOrNull()
            when {
                error == null -> {
                    logger.debug { "Sent ${batch.size} operations" }
                    store.delete(ids)
                    return
                }

                error is PermanentSendException -> {
                    logger.error(error) { "Backend rejected a batch; dropped its ${batch.size} operations" }
                    store.delete(ids)
                    return
                }

                else -> {
                    store.release(ids)                               // unlock + bump retry_count
                    // Offline and server hiccups are routine and retried: no stack trace.
                    logger.info {
                        "Batch of ${batch.size} operations not sent (${error.message ?: error.javaClass.simpleName}); " +
                            "retrying with backoff"
                    }
                    retryPolicy.backoff(batch.maxOf { it.retryCount } + 1, error)
                    if (!gate.isOnline()) return                     // consumeLoop waits for connectivity
                }
            }
        }
    }

    private companion object {
        const val STORE_ERROR_DELAY_MS = 30_000L
    }
}
