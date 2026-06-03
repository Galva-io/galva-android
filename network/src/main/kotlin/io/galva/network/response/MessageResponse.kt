package io.galva.network.response

data class MessageResponse(
    val id: String,
    val payload: String?,
    val valid: Boolean,
    val webviewVersion: String,

)