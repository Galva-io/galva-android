package io.galva.iam.bridge

import io.galva.common.InternalGalvaApi
import io.galva.network.HttpMethod
import io.galva.network.request.APIFetchRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI

/*
 * Payload parsers for the bridge methods. Each validates like the iOS SDK and throws
 * [BridgeException] (`invalidPayload`) instead of dropping the call, so the bundle's Promise
 * always settles.
 */

@InternalGalvaApi
data class PurchaseRequest(
    val productId: String,
    val basePlanId: String,
    /** Promotional offer id; null purchases the base plan. */
    val offerId: String?,
)

@InternalGalvaApi
data class ProductPriceRequest(val productId: String, val basePlanId: String, val offerId: String?)

@InternalGalvaApi
data class ShowAlertOptions(
    val title: String?,
    val message: String?,
    val actions: List<AlertAction>,
)

@InternalGalvaApi
data class AlertAction(val id: String, val title: String, val style: Style) {
    enum class Style { DEFAULT, CANCEL, DESTRUCTIVE }
}

@InternalGalvaApi
object BridgeRequests {
    /** HTTP methods the bundle may proxy through apiFetch (same allowlist as iOS). */
    private val API_FETCH_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

    /** Android's AlertDialog has three button slots (positive, neutral, negative). */
    private const val MAX_ALERT_ACTIONS = 3

    /**
     * `{ productId, basePlanId, promotionalOffer? }`. `promotionalOffer` is the offer id, either
     * as a string or as `{ offerId }`; absent or null buys the base plan.
     */
    fun parsePurchase(payload: JsonObject?): PurchaseRequest {
        val productId = payload.requireString("productId")
        val basePlanId = payload.requireString("basePlanId")
        val offerId = when (val offer = payload?.get("promotionalOffer")) {
            null, JsonNull -> null
            is JsonPrimitive -> offer.stringOrNull()?.takeIf { it.isNotEmpty() }
                ?: throw invalidPayload("promotionalOffer must be a non-empty offer id")
            is JsonObject -> offer.stringOrNull("offerId")?.takeIf { it.isNotEmpty() }
                ?: throw invalidPayload("promotionalOffer.offerId missing")
            else -> throw invalidPayload("promotionalOffer must be a string or an object")
        }
        return PurchaseRequest(productId, basePlanId, offerId)
    }

    fun parseProductPrice(payload: JsonObject?): ProductPriceRequest = ProductPriceRequest(
        productId = payload.requireString("productId"),
        basePlanId = payload.requireString("basePlanId"),
        offerId = payload?.stringOrNull("offerId")?.takeIf { it.isNotEmpty() },
    )

    /** `{ url }` for openDeepLink / openManageSubscription. */
    fun parseUrl(payload: JsonObject?): String {
        val raw = payload?.stringOrNull("url")?.trim()
        val hasScheme = raw != null && runCatching { URI(raw).scheme != null }.getOrDefault(false)
        if (raw == null || !hasScheme) throw invalidPayload("Missing or malformed URL")
        return raw
    }

    /**
     * `{ path, method?, body?, headers?, shouldRetry? }`. A string body is sent as-is; any other
     * JSON body is serialized with a default `Content-Type: application/json`.
     */
    fun parseApiFetch(payload: JsonObject?): APIFetchRequest {
        val path = payload?.stringOrNull("path")?.trim()
        if (payload == null || path.isNullOrEmpty()) throw invalidPayload("apiFetch requires a non-empty path")

        val methodName = payload.stringOrNull("method")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "GET"
        if (methodName !in API_FETCH_METHODS) throw invalidPayload("apiFetch method not allowed: $methodName")
        val method = HttpMethod.valueOf(methodName)

        val headers = (payload["headers"] as? JsonObject).orEmpty()
            .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.stringOrNull()?.let { key to it } }
            .toMap(LinkedHashMap())

        val rawBody = payload["body"]
        val body = when {
            rawBody == null || rawBody is JsonNull -> null
            rawBody is JsonPrimitive && rawBody.isString -> rawBody.content
            else -> {
                if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    headers["Content-Type"] = "application/json"
                }
                rawBody.toString()
            }
        }
        if (body != null && (method == HttpMethod.GET || method == HttpMethod.HEAD)) {
            throw invalidPayload("apiFetch body is not allowed for $methodName")
        }

        val shouldRetry = (payload["shouldRetry"] as? JsonPrimitive)?.booleanOrNull ?: false
        return APIFetchRequest(path, method, body, headers, shouldRetry)
    }

    /** `{ title?, message?, actions: [{ id, title, style? }] }`. */
    fun parseShowAlert(payload: JsonObject?): ShowAlertOptions {
        if (payload == null) throw invalidPayload("showAlert requires a payload")
        val rawActions = payload["actions"] as? JsonArray
        if (rawActions.isNullOrEmpty()) throw invalidPayload("showAlert requires a non-empty actions array")
        val actions = rawActions.map { raw ->
            val action = raw as? JsonObject ?: throw invalidPayload("showAlert action must be an object")
            val id = action.stringOrNull("id")?.takeIf { it.isNotEmpty() }
                ?: throw invalidPayload("showAlert action requires a non-empty id")
            val title = action.stringOrNull("title")?.takeIf { it.isNotEmpty() }
                ?: throw invalidPayload("showAlert action requires a non-empty title")
            val style = when (action.stringOrNull("style")?.lowercase()) {
                "cancel" -> AlertAction.Style.CANCEL
                "destructive" -> AlertAction.Style.DESTRUCTIVE
                else -> AlertAction.Style.DEFAULT
            }
            AlertAction(id, title, style)
        }
        if (actions.count { it.style == AlertAction.Style.CANCEL } > 1) {
            throw invalidPayload("showAlert allows at most one cancel action")
        }
        if (actions.size > MAX_ALERT_ACTIONS) {
            throw invalidPayload("showAlert allows at most $MAX_ALERT_ACTIONS actions on Android")
        }
        return ShowAlertOptions(payload.stringOrNull("title"), payload.stringOrNull("message"), actions)
    }

    private fun JsonObject?.requireString(key: String): String =
        this?.stringOrNull(key)?.takeIf { it.isNotEmpty() } ?: throw invalidPayload("Missing $key")

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.stringOrNull()

    private fun JsonPrimitive.stringOrNull(): String? = takeIf { it.isString }?.content
}
