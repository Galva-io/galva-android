package io.galva.network.android

import io.galva.common.InternalGalvaApi
import io.galva.common.logger.Logger
import io.galva.network.HttpClient
import io.galva.network.HttpHeaders
import io.galva.network.HttpRequest
import io.galva.network.HttpResponse
import io.galva.network.NetworkError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

@InternalGalvaApi
class HttpUrlConnectionClient(
    private val apiKey: String,
    private val sdkVersion: String,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): HttpClient {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(ioDispatcher) {
        val startNs = System.nanoTime()
        val url = URL(request.url)
        val connection =   (url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method.name
            connectTimeout = request.timeOut.inWholeMilliseconds.toInt()
            readTimeout = request.timeOut.inWholeMilliseconds.toInt()
            doInput = true
            instanceFollowRedirects = true
        }
        try {
            connection.addRequestProperty("Content-Type", "application/json")
            connection.addRequestProperty("x-sdk-version", sdkVersion)
            connection.addRequestProperty("X-API-Key", apiKey)
            request.headers.forEach { name, value ->
                connection.addRequestProperty(name, value)
            }
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { output ->
                    OutputStreamWriter(output, StandardCharsets.UTF_8).use { writer ->
                        writer.write(body)
                        writer.flush()
                    }
                }
            }
            val status = connection.responseCode
            val responseBody = readResponseBody(connection, status)
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }

            val headersBuilder = HttpHeaders.Builder()
            responseHeaders.forEach { (name, value) ->
                headersBuilder.add(name, value.joinToString(","))
            }
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            logger.debug { "${request.method} ${request.url} → $status (${responseBody.length} bytes, ${durationMs} ms)" }
            // Bodies can carry user data: VERBOSE only.
            logger.verbose {
                if (responseBody.length > 1000) {
                    "${request.method} ${request.url} → $status body:${responseBody.take(1000)}... (truncated)"
                } else {
                    "${request.method} ${request.url} → $status body:$responseBody"
                }
            }
            HttpResponse(
                status = status,
                body = responseBody,
                headers = headersBuilder.build(),
            )
        } catch (t: IOException) {
            // Offline is routine; the caller logs what the failure means for it.
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            logger.debug { "${request.method} ${request.url} → ${t.javaClass.simpleName}: ${t.message} (${durationMs} ms)" }
            // A failed connection may be half-read: drop it instead of returning it to the pool.
            connection.disconnect()
            throw t.toNetworkError()
        }
        // No disconnect() on success: the response stream was read to the end and closed, which
        // returns the connection to the keep-alive pool. The next request to the backend then
        // skips DNS, the TCP connect and the TLS handshake.

    }
    private fun readResponseBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: return ""
        }
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }
    }

    private fun IOException.toNetworkError(): NetworkError = when (this) {
        is SocketTimeoutException -> NetworkError.Timeout(this)
        is UnknownHostException -> NetworkError.NoConnectivity(this)
        else -> NetworkError.Transport(message ?: "Network error", this)
    }
}