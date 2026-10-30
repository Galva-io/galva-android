package iam

import io.galva.iam.bridge.BridgeError
import io.galva.iam.bridge.JSBridge
import io.galva.iam.bridge.JSBridgeCallback
import io.galva.iam.bridge.ProductPriceRequest
import io.galva.iam.bridge.PurchaseRequest
import io.galva.iam.bridge.ShowAlertOptions
import io.galva.network.request.APIFetchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JSBridgeTest {

    private class RecordingCallback : JSBridgeCallback {
        val calls = mutableListOf<String>()
        val errors = mutableListOf<Pair<String, BridgeError.Code>>()

        override fun onReady(requestId: String) { calls += "ready:$requestId" }
        override fun onDismiss(requestId: String) { calls += "dismiss:$requestId" }
        override fun onGetPageContext(requestId: String) { calls += "pageContext:$requestId" }
        override fun onGetMessageData(requestId: String) { calls += "messageData:$requestId" }
        override fun onRequestPurchase(requestId: String, request: PurchaseRequest) { calls += "purchase:${request.productId}" }
        override fun onOpenManageSubscription(requestId: String, url: String) { calls += "manage:$url" }
        override fun onOpenDeepLink(requestId: String, url: String) { calls += "deeplink:$url" }
        override fun onGetProductPrice(requestId: String, request: ProductPriceRequest) { calls += "price:${request.productId}" }
        override fun onDoApiFetch(requestId: String, payload: APIFetchRequest) { calls += "apiFetch:${payload.path}" }
        override fun onShowAlert(requestId: String, option: ShowAlertOptions) { calls += "alert:${option.actions.size}" }
        override fun onBridgeError(requestId: String, error: BridgeError) { errors += requestId to error.code }
    }

    private val callback = RecordingCallback()
    private val bridge = JSBridge(callback)

    @Test
    fun `valid calls are dispatched`() {
        bridge.postMessage("""{"name":"ready","requestId":"r1","payload":null}""")
        bridge.postMessage("""{"name":"requestPurchase","requestId":"r2","payload":{"productId":"pro","basePlanId":"m"}}""")

        assertEquals(listOf("ready:r1", "purchase:pro"), callback.calls)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun `unknown method is answered with unknownMethod`() {
        bridge.postMessage("""{"name":"selfDestruct","requestId":"r1","payload":{}}""")

        assertEquals(listOf("r1" to BridgeError.Code.UNKNOWN_METHOD), callback.errors)
    }

    @Test
    fun `invalid payload is answered with invalidPayload instead of being dropped`() {
        bridge.postMessage("""{"name":"requestPurchase","requestId":"r1","payload":{"basePlanId":"m"}}""")
        bridge.postMessage("""{"name":"apiFetch","requestId":"r2","payload":{}}""")
        bridge.postMessage("""{"name":"openDeepLink","requestId":"r3","payload":null}""")

        assertEquals(
            listOf(
                "r1" to BridgeError.Code.INVALID_PAYLOAD,
                "r2" to BridgeError.Code.INVALID_PAYLOAD,
                "r3" to BridgeError.Code.INVALID_PAYLOAD,
            ),
            callback.errors,
        )
        assertTrue(callback.calls.isEmpty())
    }

    @Test
    fun `undecodable envelope is ignored because there is no requestId to answer`() {
        bridge.postMessage("not json")

        assertTrue(callback.calls.isEmpty())
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun `a failing handler is answered with an error instead of crashing the JavaBridge thread`() {
        val failing = object : JSBridgeCallback by callback {
            override fun onRequestPurchase(requestId: String, request: PurchaseRequest) = error("boom")
        }

        JSBridge(failing).postMessage(
            """{"name":"requestPurchase","requestId":"r1","payload":{"productId":"pro","basePlanId":"monthly"}}"""
        )

        assertEquals(listOf("r1" to BridgeError.Code.PURCHASE_FAILED), callback.errors)
    }
}
