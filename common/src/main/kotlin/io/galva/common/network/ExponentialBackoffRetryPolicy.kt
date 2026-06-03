package io.galva.common.network

import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExponentialBackoffRetryPolicy(
    private val base: Duration = 1.seconds,
    private val cap: Duration = 5.minutes,
    private val random: Random = Random.Default,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },   // injectable for tests
) : RetryPolicy {

    override suspend fun backoff(runAttemptCount: Int, error: Throwable) {
        if(error is RetryAfterException){
            sleep(error.retryAfter ?: computeExponential(runAttemptCount))
        }else{
           val duration = computeExponential(runAttemptCount)
           sleep(duration)
        }
    }
    private fun computeExponential(attempt: Int): Duration{
        val expMs = (base.inWholeMilliseconds shl attempt.coerceAtMost(30))
            .coerceAtMost(cap.inWholeMilliseconds)
        val jitteredMs = random.nextLong(0, expMs.coerceAtLeast(1))  // FULL jitter (AWS)
        return jitteredMs.milliseconds
    }
}