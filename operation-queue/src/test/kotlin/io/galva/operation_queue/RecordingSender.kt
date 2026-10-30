package io.galva.operation_queue

import io.galva.operation_queue.local.StoredOperation
import kotlinx.coroutines.delay
import kotlin.time.Duration

class RecordingSender(
    var failuresQueue: ArrayDeque<Throwable> = ArrayDeque(),
    private val clock: Clock = Clock { 0L },
) : BatchSender {
    val batches = mutableListOf<List<StoredOperation>>()
    val sendTimestamps = mutableListOf<Long>()
    var sendDelay: Duration = Duration.ZERO
    fun queueFailure(t: Throwable = RuntimeException()) { failuresQueue.addLast(t) }

    override suspend fun send(batch: List<StoredOperation>): Result<Unit> {
        sendTimestamps += clock.nowMs()
        if (sendDelay > Duration.ZERO) delay(sendDelay)
        batches += batch
        return failuresQueue.removeFirstOrNull()
            ?.let { Result.failure(it) } ?: Result.success(Unit)
    }
}