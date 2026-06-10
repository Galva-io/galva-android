package io.galva.network.response

import kotlinx.serialization.json.JsonObject


data class MessageResponse(
    val id: String,
    val payload: JsonObject?,
    val valid: Boolean,
    val webviewVersion: String,

)