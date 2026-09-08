package io.galva.network.request.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("track-push-communication")
data class TrackPushNotificationMessage(
    val event: String,
    val timestamp: String,
    val communicationId: String,
    @SerialName("context")
    val context: MessageContext? = null,
): BatchMessage() {}
