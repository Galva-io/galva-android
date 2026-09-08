package io.galva.common.network

import kotlin.time.Duration

interface RetryPolicy {
    suspend fun backoff(runAttemptCount:Int, error: Throwable)
}

class RetryAfterException(
    val retryAfter: Duration?,
    cause: Throwable? = null,
) : RuntimeException("Server requested retry after $retryAfter", cause)