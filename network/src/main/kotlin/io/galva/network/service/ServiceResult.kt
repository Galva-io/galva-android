package io.galva.network.service

import io.galva.network.HttpHeaders
import io.galva.network.HttpResponse
import io.galva.network.NetworkError

sealed class ServiceResult<out T> {

    data class Success<T>(val value: T) : ServiceResult<T>()

    /** Server returned a non-2xx response. [retryable] hints whether the caller should retry. */
    data class Failure(
        val status: Int,
        val body: String,
        val headers: HttpHeaders,
        val retryable: Boolean,
    ) : ServiceResult<Nothing>()

    /** Transport-level failure: timeout, DNS, cancellation. */
    data class Error(val error: NetworkError) : ServiceResult<Nothing>()

    data class UnknownError(val exception: Exception): ServiceResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    val isError: Boolean get() = this is Error

    inline fun <R> map(transform: (T) -> R): ServiceResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
        is Error -> this
        is UnknownError -> this
    }
}

internal fun <T> HttpResponse.toServiceResult(parse: (String) -> T): ServiceResult<T> =
    if (isSuccessful) ServiceResult.Success(parse(body))
    else ServiceResult.Failure(status = status, body = body, retryable = isRetryable, headers = headers)