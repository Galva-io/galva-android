package io.galva.iam

import io.galva.network.HttpClient
import io.galva.network.HttpHeaders
import io.galva.network.HttpMethod
import io.galva.network.HttpRequest
import io.galva.network.HttpResponse
import io.galva.network.NetworkError
import java.util.concurrent.CopyOnWriteArrayList

class FakeHttpClient : HttpClient {

    /** Every request observed, in arrival order. */
    val recorded: MutableList<HttpRequest> = CopyOnWriteArrayList()

    private val rules: MutableList<Rule> = CopyOnWriteArrayList()

    private sealed class Rule {
        abstract val matcher: (HttpRequest) -> Boolean
        abstract suspend fun resolve(request: HttpRequest): HttpResponse
        open val consumeOnUse: Boolean = true

        data class Once(
            override val matcher: (HttpRequest) -> Boolean,
            val response: HttpResponse,
        ) : Rule() {
            override suspend fun resolve(request: HttpRequest) = response
        }

        data class OnceError(
            override val matcher: (HttpRequest) -> Boolean,
            val error: NetworkError,
        ) : Rule() {
            override suspend fun resolve(request: HttpRequest): HttpResponse = throw error
        }

        data class Always(
            override val matcher: (HttpRequest) -> Boolean,
            val response: HttpResponse,
        ) : Rule() {
            override suspend fun resolve(request: HttpRequest) = response
            override val consumeOnUse: Boolean = false
        }

        data class AlwaysError(
            override val matcher: (HttpRequest) -> Boolean,
            val error: NetworkError,
        ) : Rule() {
            override suspend fun resolve(request: HttpRequest): HttpResponse = throw error
            override val consumeOnUse: Boolean = false
        }

        data class Dynamic(
            override val matcher: (HttpRequest) -> Boolean,
            val handler: suspend (HttpRequest) -> HttpResponse,
        ) : Rule() {
            override suspend fun resolve(request: HttpRequest) = handler(request)
            override val consumeOnUse: Boolean = false
        }
    }

    // ── Enqueue helpers (single-use, FIFO) ─────────────────────────────────

    /** Enqueue a single response that matches any request. */
    fun enqueue(response: HttpResponse): FakeHttpClient = apply {
        rules += Rule.Once({ true }, response)
    }

    /** Enqueue a single response that only matches when [matcher] is true. */
    fun enqueue(matcher: (HttpRequest) -> Boolean, response: HttpResponse): FakeHttpClient = apply {
        rules += Rule.Once(matcher, response)
    }

    /** Enqueue a status-only response. */
    fun enqueueStatus(
        status: Int,
        body: String = "",
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): FakeHttpClient = apply {
        rules += Rule.Once({ true }, HttpResponse(status, headers, body))
    }

    /** Enqueue a status-only response for matching requests. */
    fun enqueueStatus(
        matcher: (HttpRequest) -> Boolean,
        status: Int,
        body: String = "",
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): FakeHttpClient = apply {
        rules += Rule.Once(matcher, HttpResponse(status, headers, body))
    }

    /** Enqueue a single throw — the next matching request raises [error]. */
    fun enqueueError(error: NetworkError): FakeHttpClient = apply {
        rules += Rule.OnceError({ true }, error)
    }

    fun enqueueError(matcher: (HttpRequest) -> Boolean, error: NetworkError): FakeHttpClient = apply {
        rules += Rule.OnceError(matcher, error)
    }

    // ── Persistent responders ──────────────────────────────────────────────

    /** Respond to every matching request with [response]. */
    fun respondAlways(
        matcher: (HttpRequest) -> Boolean = { true },
        response: HttpResponse,
    ): FakeHttpClient = apply {
        rules += Rule.Always(matcher, response)
    }

    fun respondAlways(
        matcher: (HttpRequest) -> Boolean = { true },
        status: Int,
        body: String = "",
        headers: HttpHeaders = HttpHeaders.EMPTY,
    ): FakeHttpClient = apply {
        rules += Rule.Always(matcher, HttpResponse(status, headers, body))
    }

    /** Throw [error] for every matching request. */
    fun failAlways(
        matcher: (HttpRequest) -> Boolean = { true },
        error: NetworkError,
    ): FakeHttpClient = apply {
        rules += Rule.AlwaysError(matcher, error)
    }

    /** Dynamic responder — handler can read the request and return any response. */
    fun respondWith(
        matcher: (HttpRequest) -> Boolean = { true },
        handler: suspend (HttpRequest) -> HttpResponse,
    ): FakeHttpClient = apply {
        rules += Rule.Dynamic(matcher, handler)
    }

    // ── Inspection / cleanup ───────────────────────────────────────────────

    fun lastRequest(): HttpRequest =
        recorded.lastOrNull() ?: error("FakeHttpClient: no recorded requests")

    fun requestCount(): Int = recorded.size

    fun clearRecorded() = recorded.clear()

    fun clearRules() = rules.clear()

    fun reset() {
        clearRecorded()
        clearRules()
    }

    // ── HttpClient impl ────────────────────────────────────────────────────

    override suspend fun execute(request: HttpRequest): HttpResponse {
        recorded += request
        val rule = rules.firstOrNull { it.matcher(request) }
            ?: error("FakeHttpClient: no rule matched ${request.method} ${request.url}")

        if (rule.consumeOnUse) rules.remove(rule)
        return rule.resolve(request)                            // may throw NetworkError
    }
}

object RequestMatchers {

    fun path(path: String): (HttpRequest) -> Boolean = {
        it.url.removePrefix("https://").removePrefix("http://")
            .substringAfter("/", "").substringBefore("?") == path.removePrefix("/")
    }

    fun method(method: HttpMethod): (HttpRequest) -> Boolean = { it.method == method }

    fun url(url: String): (HttpRequest) -> Boolean = { it.url == url }

    fun urlContains(substring: String): (HttpRequest) -> Boolean = { substring in it.url }

    fun header(name: String, value: String): (HttpRequest) -> Boolean = {
        it.headers[name] == value
    }

    infix fun ((HttpRequest) -> Boolean).and(other: (HttpRequest) -> Boolean): (HttpRequest) -> Boolean =
        { req -> this(req) && other(req) }

    infix fun ((HttpRequest) -> Boolean).or(other: (HttpRequest) -> Boolean): (HttpRequest) -> Boolean =
        { req -> this(req) || other(req) }
}