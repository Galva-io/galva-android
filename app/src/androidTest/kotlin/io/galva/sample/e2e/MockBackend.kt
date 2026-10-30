package io.galva.sample.e2e

import android.os.SystemClock
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * In-process Galva backend for the E2E suite: answers every endpoint the SDK calls with canned
 * responses shaped like the real API, and records each request so tests can assert on what the SDK
 * actually sent. One instance lives for the whole instrumentation process (see [E2ETestRunner]).
 */
object MockBackend {
    const val API_KEY = "e2e-test-api-key"
    const val OFFER_TITLE = "E2E Offer"

    data class Request(
        val method: String,
        /** Path without the query string. */
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        fun json(): JSONObject = JSONObject(body)
    }

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Request>()
    private val queuedCommunications = ConcurrentLinkedQueue<String>()

    @Volatile
    private var bundleHtml = ""

    val apiUrl: String get() = "http://$HOST:${server.port}/"
    val webviewUrl: String get() = "http://$HOST:${server.port}/webview/"

    fun start(bundleHtml: String) {
        this.bundleHtml = bundleHtml
        server.dispatcher = Router
        // Binding a socket is network I/O, which StrictMode forbids on the main thread that
        // creates the Application, so it happens on a short-lived worker.
        var failure: Throwable? = null
        thread(name = "mock-backend-start") {
            runCatching { server.start(InetAddress.getByName(HOST), 0) }.onFailure { failure = it }
        }.join()
        failure?.let { throw IllegalStateException("Mock backend failed to start", it) }
    }

    /** The next poll of `GET /identities/communications` delivers [communicationId] (once). */
    fun queueCommunication(communicationId: String) {
        queuedCommunications += communicationId
    }

    /** Bundle version the mock resolves [communicationId] to; each id gets its own bundle file. */
    fun webviewVersionFor(communicationId: String) = "e2e-$communicationId"

    fun recordedRequests(): List<Request> = requests.toList()

    /** Every message of every `POST /identities/batchCollect` received so far, in arrival order. */
    fun batchMessages(): List<JSONObject> = requests
        .filter { it.method == "POST" && it.path == BATCH_COLLECT }
        .flatMap { request -> request.json().getJSONArray("messages").objects() }

    fun awaitRequest(description: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, match: (Request) -> Boolean): Request =
        awaitValue("request: $description", timeoutMs) { requests.firstOrNull(match) }

    fun awaitBatchMessage(description: String, timeoutMs: Long = BATCH_TIMEOUT_MS, match: (JSONObject) -> Boolean): JSONObject =
        awaitValue("batchCollect message: $description", timeoutMs) { batchMessages().firstOrNull(match) }

    private object Router : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl
            val path = url?.encodedPath ?: request.path.orEmpty().substringBefore('?')
            val method = request.method.orEmpty()
            requests += Request(
                method = method,
                path = path,
                query = url?.let { u -> u.queryParameterNames.associateWith { u.queryParameter(it).orEmpty() } }.orEmpty(),
                headers = request.headers.toMultimap().mapValues { it.value.joinToString(",") },
                body = request.body.readUtf8(),
            )
            val resolveId = RESOLVE_PATH.matchEntire(path)?.groupValues?.get(1)
            return when {
                method == "POST" && path == "/sdk/initialize" -> json(INITIALIZE)
                method == "POST" && path == BATCH_COLLECT -> json("""{"success":true}""")
                method == "GET" && path == COMMUNICATIONS -> json(communicationsPage())
                method == "POST" && resolveId != null -> json(resolved(resolveId))
                method == "POST" && path == "/v1/transactions/observe" -> json("{}")
                method == "GET" && path.startsWith("/webview/") && path.endsWith(".html") ->
                    MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(bundleHtml)
                // apiFetch targets used by the test bundle.
                path.startsWith("/e2e/") -> json(JSONObject().put("received", true).put("path", path).toString())
                else -> MockResponse().setResponseCode(404)
            }
        }

        private fun communicationsPage(): String {
            val items = JSONArray()
            while (true) {
                val id = queuedCommunications.poll() ?: break
                items.put(
                    JSONObject()
                        .put("id", id)
                        .put("type", "trial-rescue-in-app")
                        .put("workflowType", "trial-rescue")
                        .put("createdAt", "2026-06-18T10:30:00.000Z")
                )
            }
            return JSONObject().put("success", true).put("data", items)
                .put("meta", JSONObject().put("nextCursor", JSONObject.NULL)).toString()
        }

        private fun resolved(communicationId: String): String {
            val payload = JSONObject().put("title", OFFER_TITLE).put("communicationId", communicationId)
            val data = JSONObject()
                .put("valid", true)
                .put("webviewVersion", webviewVersionFor(communicationId))
                .put("payload", payload)
            return JSONObject().put("data", data).toString()
        }

        private fun json(body: String) =
            MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private const val HOST = "127.0.0.1"
    const val BATCH_COLLECT = "/identities/batchCollect"
    const val COMMUNICATIONS = "/identities/communications"
    private val RESOLVE_PATH = Regex("/identities/communications/([^/]+)/resolve")

    /** No bundles to prefetch (so each message exercises the download), SDK minimum batch window. */
    private const val INITIALIZE =
        """{"data":{"webviewVersions":[],"batchCollection":{"flushSize":10,"flushIntervalMs":10000},"playstore":{"productIds":[]}}}"""

    const val DEFAULT_TIMEOUT_MS = 20_000L

    /** The SDK flushes its event queue every 10 s at the fastest (the server can only raise it). */
    const val BATCH_TIMEOUT_MS = 30_000L
}

/** Polls [probe] until it returns non-null, failing with [description] after [timeoutMs]. */
fun <T : Any> awaitValue(description: String, timeoutMs: Long = MockBackend.DEFAULT_TIMEOUT_MS, probe: () -> T?): T {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        probe()?.let { return it }
        if (SystemClock.uptimeMillis() > deadline) {
            throw AssertionError("Timed out after ${timeoutMs}ms waiting for $description")
        }
        SystemClock.sleep(POLL_INTERVAL_MS)
    }
}

fun awaitCondition(description: String, timeoutMs: Long = MockBackend.DEFAULT_TIMEOUT_MS, condition: () -> Boolean) {
    awaitValue(description, timeoutMs) { true.takeIf { condition() } }
}

private const val POLL_INTERVAL_MS = 100L
