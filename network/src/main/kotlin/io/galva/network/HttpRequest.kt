package io.galva.network

import io.galva.common.InternalGalvaApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@InternalGalvaApi
data class HttpRequest(
    val url: String,
    val method: HttpMethod,
    val headers: HttpHeaders = HttpHeaders.EMPTY,
    val query: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeOut: Duration = 30.seconds,
) {
    init {
        if (body != null) {
            require(method != HttpMethod.GET) { "GET requests must not have a body" }
        }
    }
}