package io.galva.network.response

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.json.JsonObject


@InternalGalvaApi
data class MessageResponse(
    val id: String,
    val payload: JsonObject?,
    val valid: Boolean,
    val webviewVersion: String,

)