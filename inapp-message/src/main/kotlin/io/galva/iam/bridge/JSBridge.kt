package io.galva.iam.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
    val callback: JSBridgeCallback
) {
    @OptIn(InternalSerializationApi::class)
    @JavascriptInterface
    fun postMessage(message: String) {
        // Handle the message received from JavaScript
        // You can parse the message and perform actions based on its content
        Log.e("JSBridge", "Received message: $message")
        val parsedMessage = runCatching {
            JsonUtils.defaultJson.decodeFromString<BridgeMessage>(message)
        }.getOrNull() ?: return
        Log.e("JSBridge", "Received parsedMessage: $parsedMessage")
        val method = BridgeMethod.entries.firstOrNull {
            it.methodName == parsedMessage.name
        } ?: return
        Log.e("JSBridge", "Received method: $method")
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
                Log.e("JSBridge", "GetProductPrice called with payload: ${parsedMessage.payload}")
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
                    Log.e(
                        "JSBridge",
                        "Failed to parse APIFetchRequest: ${it.message}, payload: $payload"
                    )
                }.getOrNull() ?: return
                callback.onDoApiFetch(parsedMessage.requestId, apiFetchRequest)
            }

            BridgeMethod.ShowAlert -> {
                val payload = parsedMessage.payload.toString()
                val options = runCatching {
                    JsonUtils.defaultJson.decodeFromString<ShowAlertOptions>(payload)
                }.onFailure {
                    Log.e(
                        "JSBridge",
                        "Failed to parse ShowAlertOptions: ${it.message}, payload: $payload"
                    )
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