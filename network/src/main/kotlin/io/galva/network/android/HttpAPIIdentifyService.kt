package io.galva.network.android

import io.galva.common.logger.Logger
import io.galva.network.Endpoint
import io.galva.network.HttpClient
import io.galva.network.NetworkError
import io.galva.network.RequestBuilder
import io.galva.network.request.BatchCollectRequest
import io.galva.network.request.ListCommunicationRequest
import io.galva.network.request.ResolveMessageRequest
import io.galva.network.response.CommunicationResponse
import io.galva.network.response.MessageResponse
import io.galva.network.service.IdentifyService
import io.galva.network.service.ServiceResult
import io.galva.network.service.toServiceResult
import io.galva.network.toUrl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject

class HttpAPIIdentifyService(
    private val baseURL: String,
    private val httpClient: HttpClient,
    private val json: Json = DEFAULT_JSON,
    private val logger: Logger
) : IdentifyService {
    override suspend fun sendBatchCollectRequest(request: BatchCollectRequest): ServiceResult<Unit> {
        return try {
            val body = json.encodeToString(BatchCollectRequest.serializer(), request)
            val request =
                RequestBuilder.post(Endpoint.IDENTIFY.toUrl(baseURL).plus("/batchCollect"))
                    .jsonBody(body).build()
            logger.info {
                "sendBatchCollectRequest request: ${request.url}, headers: ${request.headers}, body: ${request.body}"
            }
            httpClient.execute(request).toServiceResult { _ ->
                Unit
            }
        } catch (e: NetworkError) {
            ServiceResult.Error(e)
        } catch (e: Exception) {
            println("sendBatchCollectRequest failed with Exception: ${e.message}, cause: ${e.cause?.message}")
            ServiceResult.UnknownError(e)
        }
    }

    override suspend fun fetchCommunications(request: ListCommunicationRequest): ServiceResult<CommunicationResponse> {
        return try {
            val request =
                RequestBuilder.get(Endpoint.IDENTIFY.toUrl(baseURL).plus("/communications"))
                    .query("anonymousId", request.anonymousId).query("cursor", request.cursor ?: "")
                    .query("limit", request.limit)
                    .apply {
                        if(request.endUserId!=null){
                            query("endUserId", request.endUserId)
                        }
                    }
                    .query("channelType", request.channelType.id)
                    .build()
            logger.info {
                "fetchCommunications request: ${request.url}, headers: ${request.headers}, body: ${request.body} ${request.method}"
            }
            httpClient.execute(request).toServiceResult { responseJson ->
                DEFAULT_JSON.decodeFromString<CommunicationResponse>(responseJson)
            }
        } catch (e: NetworkError) {
            logger.error {
                "fetchCommunications failed with NetworkError: ${e.message}, cause: ${e.cause?.message}"
            }
            ServiceResult.Error(e)
        } catch (e: Exception) {
            logger.error {
                "fetchCommunications failed with Exception: ${e.message}, cause: ${e.cause?.message}"
            }
            ServiceResult.UnknownError(e)
        }
    }

    override suspend fun resolveMessage(request: ResolveMessageRequest): ServiceResult<MessageResponse> {
        return try {
            val body = Json.encodeToString(
                buildJsonObject {
                    put("devicePlatform", JsonPrimitive("android"))
                    put("anonymousId", JsonPrimitive(request.anonymousId))

                    put("bridgeProtocolVersion", JsonPrimitive(request.bridgeProtocolVersion))

                    putJsonObject("billingContext") {
                        put("countryCode", JsonPrimitive(request.countryCode))
                    }
                    if(request.endUserId != null) {
                        put("endUserId", JsonPrimitive(request.endUserId))
                    }
                }
            )

            val apiRequest =
                RequestBuilder.post(
                    Endpoint.IDENTIFY.toUrl(baseURL)
                        .plus("/communications/${request.messageId}/resolve")
                )
                    .jsonBody(body)
                    .build()
            logger.info {
                "resolveMessage request: ${apiRequest.url}, headers: ${apiRequest.headers}, body: ${apiRequest.body}"
            }
            httpClient.execute(apiRequest).toServiceResult { responseJson ->
                logger.info {
                    "resolveMessage response: $responseJson"
                }
                val json = DEFAULT_JSON.parseToJsonElement(responseJson).jsonObject
                logger.info {
                    "Parsed JSON response: $json"
                }
                val jsonData =
                    json["data"]?.jsonObject ?: throw Exception("Missing data field in response")
                val isValid = jsonData["valid"]?.jsonPrimitive?.booleanOrNull ?: false
                val payload = jsonData["payload"]?.jsonObject
                val webviewVersion = jsonData["webviewVersion"]?.jsonPrimitive?.contentOrNull
                    ?: throw Exception("Missing webviewVersion field in response")
                MessageResponse(request.messageId, payload, isValid, webviewVersion)
            }
        } catch (e: NetworkError) {
            logger.error {
                "resolveMessage failed with NetworkError: ${e.message}, cause: ${e.cause?.message}"
            }
            ServiceResult.Error(e)
        } catch (e: Exception) {
            logger.error {
                "resolveMessage failed with Exception: ${e.message}, cause: ${e.cause?.message}"
            }
            ServiceResult.UnknownError(e)
        }
    }

    private companion object {
        val DEFAULT_JSON =
            Json {
                ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator =
                "type"; explicitNulls = false
            }
    }
}