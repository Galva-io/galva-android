package io.galva.network.android

import io.galva.common.InternalGalvaApi
import io.galva.common.logger.Logger
import io.galva.network.HttpClient
import io.galva.network.HttpHeaders
import io.galva.network.NetworkError
import io.galva.network.ProxyUrlResolver
import io.galva.network.RequestBuilder
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult
import io.galva.network.service.IAMService
import io.galva.network.service.ServiceResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * Proxies apiFetch requests from in-app message bundles to the Galva API in a single attempt;
 * durable retries (`shouldRetry`) are handled by the caller's persistent queue.
 */
@InternalGalvaApi
class HttpAPIIamService(
    private val baseURL: String,
    private val httpClient: HttpClient,
    private val logger: Logger,
) : IAMService {

    override fun isValidPath(path: String): Boolean = ProxyUrlResolver.resolve(path, baseURL) != null

    override suspend fun apiFetch(apiFetchRequest: APIFetchRequest): ServiceResult<APIFetchResult> {
        val url = ProxyUrlResolver.resolve(apiFetchRequest.path, baseURL)
            ?: return ServiceResult.UnknownError(
                IllegalArgumentException("apiFetch path is invalid or targets a disallowed origin")
            )
        return try {
            val request = RequestBuilder(apiFetchRequest.method, url).apply {
                apiFetchRequest.body?.let { body(it) }
                apiFetchRequest.headers
                    .filterKeys { it.lowercase() !in RESERVED_HEADERS }
                    .forEach { (key, value) -> setHeader(key, value) }
            }.build()
            logger.verbose {
                "apiFetch request: ${request.method} ${request.url}, body: ${request.body}"
            }
            val response = httpClient.execute(request)
            val result = toResult(response.status, response.headers, response.body)
            logger.verbose {
                "apiFetch response: status=${result.status}, body=${response.body}, bodyType=${result.bodyType}"
            }
            ServiceResult.Success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkError) {
            // Reported to the page as a transport error; offline is routine.
            logger.debug { "apiFetch ${apiFetchRequest.method} ${apiFetchRequest.path} not sent: ${e.message}" }
            ServiceResult.Error(e)
        } catch (e: Exception) {
            logger.error(e) { "apiFetch ${apiFetchRequest.method} ${apiFetchRequest.path} failed" }
            ServiceResult.UnknownError(e)
        }
    }

    private fun toResult(status: Int, headers: HttpHeaders, body: String): APIFetchResult {
        val lowerCased = headers.values().mapKeys { it.key.lowercase() }
        val parsedJson = if (body.isNotEmpty() && lowerCased["content-type"]?.contains("json", ignoreCase = true) == true) {
            runCatching { Json.parseToJsonElement(body) }.getOrNull()
        } else {
            null
        }
        return APIFetchResult(
            status = status,
            ok = status in 200..299,
            headers = lowerCased,
            body = parsedJson ?: JsonPrimitive(body),
            bodyType = if (parsedJson != null) "json" else "text",
        )
    }

    private companion object {
        /** SDK-managed headers the bundle must not set or spoof. */
        val RESERVED_HEADERS = setOf("x-api-key", "x-sdk-version")
    }
}
