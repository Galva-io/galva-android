package io.galva.network.request

import io.galva.common.InternalGalvaApi
import io.galva.network.HttpMethod
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
data class APIFetchRequest(
    val path:String,
    val method: HttpMethod,
    val body:String? = null,
    val headers : Map<String, String> = emptyMap(),
    val shouldRetry: Boolean = false
)