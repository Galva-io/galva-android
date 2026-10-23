package io.galva.iam.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.common.utils.JsonUtils
import io.galva.network.request.APIFetchRequest
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface JSBridgeCallback {
    fun onReady(requestId: String)
    fun onDismiss(requestId: String)
    fun onGetPageContext(requestId: String)
    fun onGetMessageData(requestId: String)
    fun onRequestPurchase(requestId: String, productId: String,basePlanId:String, offerId: String)
    fun onOpenManageSubscription(requestId: String, url: String)
    fun onOpenDeepLink(requestId: String, url: String)
    fun onGetProductPrice(
        requestId: String, productId: String, basePlanId: String, offerId: String?
    )

    fun onDoApiFetch(requestId: String, payload: APIFetchRequest)

    @OptIn(InternalSerializationApi::class)
    fun onShowAlert(requestId: String, option:ShowAlertOptions)

}
@InternalSerializationApi @Serializable
data class ShowAlertOptions(
    val title: String, val message: String, val actions: List<AlertAction> = emptyList()
)

@InternalSerializationApi
@Serializable
data class AlertAction(
    val id: String, val title: String, val style: String
)

class JSBridge(
    val callback: JSBridgeCallback,
    private val logger: Logger = NoOpLogger,
) {
    @OptIn(InternalSerializationApi::class)
    @JavascriptInterface
    fun postMessage(message: String) {
        // Payloads can contain personal data, so they are only logged at VERBOSE.
        logger.verbose { "Bridge in: $message" }
        val parsedMessage = runCatching {
            JsonUtils.defaultJson.decodeFromString<BridgeMessage>(message)
        }.onFailure {
            logger.warn(it) { "Bridge: failed to decode envelope" }
        }.getOrNull() ?: return
        val method = BridgeMethod.entries.firstOrNull {
            it.methodName == parsedMessage.name
        } ?: run {
            logger.warn { "Bridge: unknown method ${parsedMessage.name}" }
            return
        }
        logger.debug { "Bridge in: ${method.methodName} (${parsedMessage.requestId})" }
        when (method) {
            BridgeMethod.Ready -> {

            }

            BridgeMethod.Dismiss -> {
                callback.onDismiss(parsedMessage.requestId)
            }

            BridgeMethod.GetPageContext -> {
                callback.onGetPageContext(parsedMessage.requestId)
            }

            BridgeMethod.GetMessageData -> {
                callback.onGetMessageData(parsedMessage.requestId)
            }

            BridgeMethod.RequestPurchase -> {
                val payload = runCatching {
                    parsedMessage.payload
                }.getOrNull() ?: return
                val productId = payload["productId"]?.jsonPrimitive?.content ?: return
                val basePlanId = payload["basePlanId"]?.jsonPrimitive?.content ?: return
                val offerToken = payload["promotionalOffer"]?.jsonPrimitive?.content ?: return
                callback.onRequestPurchase(parsedMessage.requestId, productId,basePlanId, offerToken)
            }

            BridgeMethod.OpenManageSubscription -> {
                val url = parsedMessage.payload?.get("url")?.jsonPrimitive?.content ?: return
                callback.onOpenManageSubscription(parsedMessage.requestId, url)
            }

            BridgeMethod.OpenDeepLink -> {
                val url = parsedMessage.payload?.get("url")?.jsonPrimitive?.content ?: return
                callback.onOpenDeepLink(parsedMessage.requestId, url)
            }

            BridgeMethod.GetProductPrice -> {
                val payload = parsedMessage.payload ?: return
                val productId = payload["productId"]?.jsonPrimitive?.content ?: return
                val basePlanId = payload["basePlanId"]?.jsonPrimitive?.content ?: return
                val offerId = payload["offerId"]?.jsonPrimitive?.content
                callback.onGetProductPrice(parsedMessage.requestId, productId, basePlanId, offerId)
            }

            BridgeMethod.DoApiFetch -> {
                val payload = parsedMessage.payload.toString()
                val apiFetchRequest = runCatching {
                    JsonUtils.defaultJson.decodeFromString<APIFetchRequest>(payload)
                }.onFailure {
                    logger.warn(it) { "Bridge: invalid apiFetch payload" }
                }.getOrNull() ?: return
                callback.onDoApiFetch(parsedMessage.requestId, apiFetchRequest)
            }

            BridgeMethod.ShowAlert -> {
                val payload = parsedMessage.payload.toString()
                val options = runCatching {
                    JsonUtils.defaultJson.decodeFromString<ShowAlertOptions>(payload)
                }.onFailure {
                    logger.warn(it) { "Bridge: invalid showAlert payload" }
                }.getOrNull() ?: return
                callback.onShowAlert(parsedMessage.requestId, options)
            }
        }
    }

}

enum class BridgeMethod(val methodName: String) {
    Ready("ready"), Dismiss("dismiss"), GetPageContext("getPageContext"), GetMessageData("getMessageData"), RequestPurchase(
        "requestPurchase"
    ),
    OpenManageSubscription("openManageSubscription"), OpenDeepLink("openDeepLink"), GetProductPrice(
        "getProductPrice"
    ),
    ShowAlert("showAlert"), DoApiFetch("apiFetch");
}