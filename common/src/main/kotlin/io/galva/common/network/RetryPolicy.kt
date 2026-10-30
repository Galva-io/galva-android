package io.galva.common.network

import io.galva.common.InternalGalvaApi
import kotlin.time.Duration

@InternalGalvaApi
interface RetryPolicy {
    suspend fun backoff(runAttemptCount:Int, error: Throwable)
}

@InternalGalvaApi
class RetryAfterException(
    val retryAfter: Duration?,
    cause: Throwable? = null,
) : RuntimeException("Server requested retry after $retryAfter", cause)