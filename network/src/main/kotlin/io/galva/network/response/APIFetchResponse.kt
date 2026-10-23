package io.galva.network.response

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `apiFetch` result, same shape as the iOS SDK: any completed HTTP round-trip — including 4xx / 5xx —
 * is a result, so the page can read [status] and [body].
 */
@Serializable
@InternalGalvaApi
data class APIFetchResult(
    val status: Int,
    val ok: Boolean,
    /** Response headers with lower-cased names. */
    val headers: Map<String, String>,
    /** Parsed JSON when [bodyType] is `json`, otherwise the body as a string. */
    val body: JsonElement,
    /** `json` or `text`. */
    val bodyType: String
)
