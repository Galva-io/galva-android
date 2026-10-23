package io.galva.network.request.messages

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@SerialName("track")
@InternalGalvaApi
data class TrackMessage(
    val event: String,
    val timestamp: String,
    val anonymousId: String? = null,
    @SerialName("context")
    val context: MessageContext? = null,
    val endUserId: String? = null,
    val messageId: String? = null,
    val properties: JsonObject? = null,
    val sourceId: String? = null,
    val sourceType: SourceType? = null
): BatchMessage()  {}
