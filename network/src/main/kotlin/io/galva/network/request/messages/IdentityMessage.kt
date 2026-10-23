package io.galva.network.request.messages

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@SerialName("identify")
@InternalGalvaApi
data class IdentityMessage(
    val timestamp: String,
    val anonymousId: String,
    @SerialName("context")
    val context: MessageContext? = null,
    val endUserId: String? = null,
    val messageId: String? = null,
    val traits: JsonObject? = null

) : BatchMessage() {}