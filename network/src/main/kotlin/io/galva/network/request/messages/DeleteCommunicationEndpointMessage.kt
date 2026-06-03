package io.galva.network.request.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("DeleteCommunicationEndpointMessage")
data class DeleteCommunicationEndpointMessage(
    val endpoint: EndpointNotification,
    val timestamp: String,
    @SerialName("type")
    val type: String = "delete-communication-endpoint",
    val anonymousId: String? = null,
    @SerialName("context")
    val context: MessageContext? = null,
    val endUserId: String? = null,
    val messageId: String? = null
): BatchMessage() {}
