package io.galva.network.android

import io.galva.common.logger.Logger
import io.galva.common.utils.JsonUtils
import io.galva.network.Endpoint
import io.galva.network.HttpClient
import io.galva.network.NetworkError
import io.galva.network.RequestBuilder
import io.galva.network.request.products.ListProductRequest
import io.galva.network.request.sdk.InitConfigSdkRequest
import io.galva.network.response.ListProductResponse
import io.galva.network.response.SDKInitializeResponse
import io.galva.network.service.ProductService
import io.galva.network.service.SdkService
import io.galva.network.service.ServiceResult
import io.galva.network.service.toServiceResult
import io.galva.network.toUrl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HttpAPISDKService(
    private val baseURL: String,
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val json: Json = JsonUtils.defaultJson,
) : SdkService {
    override suspend fun getInitializeConfig(request: InitConfigSdkRequest): ServiceResult<SDKInitializeResponse> {
        return try {
            val request =
                RequestBuilder.post(Endpoint.SDK.toUrl(baseURL).plus("/initialize"))
                    .jsonBody(json.encodeToString(request))
                    .build()
            logger.info {
                "getInitializeConfig request: ${request.url}, headers: ${request.headers}"
            }
            httpClient.execute(request).toServiceResult { responseJson ->
                json.decodeFromString<SDKInitializeResponse>(responseJson)
            }
        } catch (e: NetworkError) {
            logger.error {
                "getInitializeConfig failed with NetworkError: ${e.message}, cause: ${e.cause?.message}"
            }
            ServiceResult.Error(e)
        } catch (e: Exception) {
            logger.error {
                "getInitializeConfig failed with Exception: ${e.message}, cause: ${e.cause?.message}"
            }
            ServiceResult.UnknownError(e)
        }
    }
}