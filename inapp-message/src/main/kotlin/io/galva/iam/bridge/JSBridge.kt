package io.galva.iam.bridge

import io.galva.common.InternalGalvaApi
import android.webkit.JavascriptInterface
import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.common.utils.JsonUtils
import io.galva.network.request.APIFetchRequest

@InternalGalvaApi
interface JSBridgeCallback {
    fun onReady(requestId: String)
    fun onDismiss(requestId: String)
    fun onGetPageContext(requestId: String)
    fun onGetMessageData(requestId: String)
    fun onRequestPurchase(requestId: String, request: PurchaseRequest)
    fun onOpenManageSubscription(requestId: String, url: String)
    fun onOpenDeepLink(requestId: String, url: String)
    fun onGetProductPrice(requestId: String, request: ProductPriceRequest)
    fun onDoApiFetch(requestId: String, payload: APIFetchRequest)
    fun onShowAlert(requestId: String, option: ShowAlertOptions)

    /** Answer [requestId] with an error envelope instead of a result. */
    fun onBridgeError(requestId: String, error: BridgeError)
}

/**
 * Receives `galva.postMessage(json)` calls from the in-app message bundle. Every envelope that
 * carries a `requestId` gets an answer — a result from the callback or a [BridgeError] — so the
 * bundle's Promise never hangs. Only an envelope that cannot be decoded at all (no `requestId` to
 * answer) is dropped, as in the iOS SDK.
 */
@InternalGalvaApi
class JSBridge(
    val callback: JSBridgeCallback,
    private val logger: Logger = NoOpLogger,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        // Payloads can contain personal data, so they are only logged at VERBOSE.
        logger.verbose { "Bridge in: $message" }
        val parsedMessage = runCatching {
            JsonUtils.defaultJson.decodeFromString<BridgeMessage>(message)
        }.onFailure {
            logger.warn(it) { "Bridge: failed to decode envelope" }
        }.getOrNull() ?: return
        val requestId = parsedMessage.requestId
        val method = BridgeMethod.entries.firstOrNull {
            it.methodName == parsedMessage.name
        }
        if (method == null) {
            logger.warn { "Bridge: unknown method ${parsedMessage.name}" }
            callback.onBridgeError(
                requestId,
                BridgeError(BridgeError.Code.UNKNOWN_METHOD, "Unknown bridge method: ${parsedMessage.name}"),
            )
            return
        }
        logger.debug { "Bridge in: ${method.methodName} ($requestId)" }
        val payload = parsedMessage.payload
        try {
            when (method) {
                BridgeMethod.Ready -> callback.onReady(requestId)
                BridgeMethod.Dismiss -> callback.onDismiss(requestId)
                BridgeMethod.GetPageContext -> callback.onGetPageContext(requestId)
                BridgeMethod.GetMessageData -> callback.onGetMessageData(requestId)
                BridgeMethod.RequestPurchase ->
                    callback.onRequestPurchase(requestId, BridgeRequests.parsePurchase(payload))

                BridgeMethod.OpenManageSubscription ->
                    callback.onOpenManageSubscription(requestId, BridgeRequests.parseUrl(payload))

                BridgeMethod.OpenDeepLink ->
                    callback.onOpenDeepLink(requestId, BridgeRequests.parseUrl(payload))

                BridgeMethod.GetProductPrice ->
                    callback.onGetProductPrice(requestId, BridgeRequests.parseProductPrice(payload))

                BridgeMethod.DoApiFetch ->
                    callback.onDoApiFetch(requestId, BridgeRequests.parseApiFetch(payload))

                BridgeMethod.ShowAlert ->
                    callback.onShowAlert(requestId, BridgeRequests.parseShowAlert(payload))
            }
        } catch (e: BridgeException) {
            logger.warn { "Bridge: ${method.methodName} rejected — ${e.error.message}" }
            callback.onBridgeError(requestId, e.error)
        } catch (e: Exception) {
            // Runs on WebView's JavaBridge thread: an escaping exception would crash the app.
            logger.error(e) { "Bridge: ${method.methodName} failed" }
            callback.onBridgeError(requestId, BridgeError(unexpectedErrorCode(method), "Unexpected error"))
        }
    }

    private fun unexpectedErrorCode(method: BridgeMethod): BridgeError.Code = when (method) {
        BridgeMethod.RequestPurchase, BridgeMethod.GetProductPrice -> BridgeError.Code.PURCHASE_FAILED
        BridgeMethod.DoApiFetch -> BridgeError.Code.API_REQUEST_FAILED
        BridgeMethod.OpenDeepLink, BridgeMethod.OpenManageSubscription -> BridgeError.Code.URL_OPEN_FAILED
        else -> BridgeError.Code.MESSAGE_DATA_UNAVAILABLE
    }

}

@InternalGalvaApi
enum class BridgeMethod(val methodName: String) {
    Ready("ready"), Dismiss("dismiss"), GetPageContext("getPageContext"), GetMessageData("getMessageData"), RequestPurchase(
        "requestPurchase"
    ),
    OpenManageSubscription("openManageSubscription"), OpenDeepLink("openDeepLink"), GetProductPrice(
        "getProductPrice"
    ),
    ShowAlert("showAlert"), DoApiFetch("apiFetch");
}
