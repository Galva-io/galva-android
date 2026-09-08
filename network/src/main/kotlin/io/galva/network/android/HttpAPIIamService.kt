package io.galva.network.android

import io.galva.common.logger.Logger
import io.galva.common.network.RetryPolicy
import io.galva.network.Endpoint
import io.galva.network.HttpClient
import io.galva.network.HttpMethod
import io.galva.network.NetworkError
import io.galva.network.RequestBuilder
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult
import io.galva.network.service.IAMService
import io.galva.network.service.ServiceResult
import io.galva.network.toUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

class HttpAPIIamService(
    private val baseURL: String,
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val retryPolicy: RetryPolicy
) : IAMService {
    override suspend fun apiFetch(apiFetchRequest: APIFetchRequest): ServiceResult<APIFetchResult> {
        logger.info {
            "Received apiFetch request: method=${apiFetchRequest.method}, path=${apiFetchRequest.path}, headers=${apiFetchRequest.headers}, body=${apiFetchRequest.body}"
        }
        var attempt = 0
        while ((attempt == 0 || apiFetchRequest.shouldRetry) && currentCoroutineContext().isActive) {
            try {
                val url = Endpoint.EMPTY.toUrl(baseURL).plus(apiFetchRequest.path)
                val request = RequestBuilder.run {
                    when (apiFetchRequest.method) {
                        HttpMethod.GET -> get(url)
                        HttpMethod.POST -> post(url)
                        HttpMethod.PUT -> put(url)
                        HttpMethod.PATCH -> post(url)
                        HttpMethod.DELETE -> delete(url)
                    }
                }.run {
                    if (apiFetchRequest.body != null) {
                        this.jsonBody(apiFetchRequest.body)
                    } else {
                        this
                    }
                }.apply {
                    apiFetchRequest.headers.forEach { (key, value) ->
                        this.setHeader(key, value)
                    }
                }.build()
                logger.info {
                    "apiFetch request: ${request.url}, headers: ${request.headers}, body: ${request.body} ${request.method}"
                }
                val response = httpClient.execute(request)
                logger.info {
                    "Received apiFetch response check headers: status=${response.status}, check: ${response.headers["Content-Type"]}"
                }
                val bodyType =
                    if (response.headers["Content-Type"]?.contains("application/json") == true) "json" else "text"
                logger.info {
                    "apiFetch response: status=${response.status}, headers=${response.headers}, body=${response.body}, bodyType=$bodyType"
                }
                return ServiceResult.Success(
                    APIFetchResult(
                        response.status,
                        ok = response.isSuccessful,
                        headers = response.headers.values(),
                        bodyType = bodyType,
                        body = response.body
                    )
                ).also {
                    attempt++
                }
            } catch (e: NetworkError) {
                logger.error {
                    "Network error during apiFetch request: ${e.message}"
                }
                if ( apiFetchRequest.shouldRetry) {
                    attempt++
                    retryPolicy.backoff(attempt, e.cause ?: Throwable(e.message))
                } else {
                    return ServiceResult.Error(e)
                }
            } catch (e: Exception) {
                logger.error {
                    "Unexpected error during apiFetch request: ${e.message}"
                }
                if ( apiFetchRequest.shouldRetry) {
                    attempt++
                    retryPolicy.backoff(attempt, e.cause ?: Throwable(e.message))
                } else {
                    return ServiceResult.UnknownError(e)
                }

            }
        }
        return ServiceResult.UnknownError(Exception("apiFetch request failed due to coroutine cancellation"))
    }
}