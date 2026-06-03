package io.galva.operation_queue


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
    private val enqueueWake: Channel<Unit> = Channel(Channel.CONFLATED),
    private val forceWake: Channel<Unit> = Channel(Channel.CONFLATED),
) : OperationQueue {
    private val lockToken = "queue-${idGen.next()}"
    private var consumerJob: Job? = null
    private val children = mutableListOf<Job>()

    override fun start() {
        if (consumerJob != null) return
        consumerJob = scope.launch {
            store.releaseAllLocks()
            consumeLoop()
        }
        children += scope.launch { transitionTrigger.signals().collect { forceWake.trySend(Unit) } }
    }

    override suspend fun enqueue(payload: String, type: String, id: String?) {
        logger.info {
            "enqueue: adding operation to store (id=${id ?: "gen"}, type=$type) payload =${payload}"
        }
        store.insert(
            StoredOperation(
                id = id ?: idGen.next(),
                type = type,
                payload = payload,
                createdAt = clock.nowMs(),
                retryCount = 0
            )
        )
        enqueueWake.trySend(Unit)
    }

    override fun flush() {
        forceWake.trySend(Unit)
    }

    override suspend fun clear() {
        stop()
        store.clearAll()
        start()
    }

    override fun stop() {
        consumerJob?.cancel()
        children.forEach { it.cancel() }
    }

    private suspend fun consumeLoop() {
        while (currentCoroutineContext().isActive) {
            logger.info {
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
            logger.info {
                "process queue: conditions met, claiming batch"
            }
            val batch = store.claimBatch(batchPolicy.maxBatchSize, lockToken, clock.nowMs())
            logger.info {
                "process queue: claimed batch of size ${batch.size}"
            }
            if (batch.isEmpty()) continue
            logger.info {
                "process queue: sending... ${batch.size} operations"
            }
            var batchSendSuccess = false
            while (batchSendSuccess.not() && currentCoroutineContext().isActive) {
                val result = sender.send(batch)
                if (result.isSuccess) {
                    logger.info {
                        "process queue: batch sent successfully, deleting from store"
                    }
                    store.delete(batch.map { it.id })
                    batchSendSuccess = true
                    continue
                } else {
                    val err = result.exceptionOrNull() ?: continue
                    logger.error {
                        "process queue: failed to send batch: ${err.message}, releasing locks and applying backoff"
                    }
                    store.release(batch.map { it.id })           // unlock + bump retry_count
                    val attempt = batch.maxOf { it.retryCount }
                    retryPolicy.backoff(attempt, err)              // suspend before next claim
                }
            }
        }
    }
}