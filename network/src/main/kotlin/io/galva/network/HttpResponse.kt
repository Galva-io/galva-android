package io.galva.network

data class HttpResponse(
    val status: Int,
    val headers: HttpHeaders,
    val body: String,
) {
    val isSuccessful: Boolean get() = status in 200..299
    val isClientError: Boolean get() = status in 400..499
    val isServerError: Boolean get() = status in 500..599
    val isRetryable: Boolean get() = status == 408 || status == 429 || status in 500..599
}