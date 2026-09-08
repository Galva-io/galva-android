package io.galva.network.response

import kotlinx.serialization.Serializable

@Serializable
data class APIFetchResult(
    val status: Int,
    val ok: Boolean,
    val headers: Map<String, String>,
    val body: String,
    val bodyType: String
)

