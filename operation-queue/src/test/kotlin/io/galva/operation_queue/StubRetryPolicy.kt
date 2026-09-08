package io.galva.operation_queue

import io.galva.common.network.RetryPolicy
import kotlinx.coroutines.delay
import kotlin.time.Duration

class StubRetryPolicy(val backoff: Duration = Duration.ZERO) : RetryPolicy {
    var calls = 0
    override suspend fun backoff(runAttemptCount: Int, error: Throwable) {
        calls++
        if (backoff > Duration.ZERO) delay(backoff)
    }
}