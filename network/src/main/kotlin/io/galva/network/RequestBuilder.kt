package io.galva.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RequestBuilder(private val method: HttpMethod, private val url: String) {
    private var headersBuilder = HttpHeaders.Builder()
    private var body: String? = null
    private var timeout: Duration = 30.seconds

    private var queryParams = mutableListOf<Pair<String, String>>()

    fun header(name: String, value: String) = apply { headersBuilder.add(name, value) }
    fun setHeader(name: String, value: String) = apply { headersBuilder.set(name, value) }

    fun query(name: String, value: String) = apply {
        queryParams += name to value
    }
    fun query(name: String, value: Number) = apply {
        queryParams += name to value.toString()
    }
    fun query(name: String, value: Boolean) = apply {
        queryParams += name to value.toString()
    }
    /** Adds multiple values for the same key — e.g. `?tag=a&tag=b`. */
    fun queries(name: String, values: Iterable<String>) = apply {
        values.forEach { queryParams += name to it }
    }
    /** Adds all entries from a map. */
    fun queries(params: Map<String, String>) = apply {
        params.forEach { (k, v) -> queryParams += k to v }
    }
    fun jsonBody(body: String) = apply {
        this.body = body
        headersBuilder.set("Content-Type", "application/json; charset=utf-8")
    }

    private fun buildUrl(base: String, params: List<Pair<String, String>>): String {
        if (params.isEmpty()) return base

        val separator = if ('?' in base) '&' else '?'
        val encoded = params.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        return "$base$separator$encoded"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    fun timeout(value: Duration) = apply { this.timeout = value }

    fun build(): HttpRequest = HttpRequest(
        method = method,
        url = buildUrl(url, queryParams),
        headers = headersBuilder.build(),
        body = body,
        timeOut = timeout,
        query = queryParams.toMap()
    )

    companion object {
        @JvmStatic
        fun get(url: String) = RequestBuilder(HttpMethod.GET, url)

        @JvmStatic
        fun post(url: String) = RequestBuilder(HttpMethod.POST, url)

        @JvmStatic
        fun put(url: String) = RequestBuilder(HttpMethod.PUT, url)

        @JvmStatic
        fun patch(url: String) = RequestBuilder(HttpMethod.PATCH, url)

        @JvmStatic
        fun delete(url: String) = RequestBuilder(HttpMethod.DELETE, url)
    }
}