package io.galva.network.request.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("create-communication-endpoint")
data class CreateCommunicationEndpointMessage(
    @SerialName("endpoint")
    val endpoint: EndpointNotification,
    val timestamp: String,
    val anonymousId: String? = null,
    @SerialName("context")
    val context: MessageContext? = null,
    val endUserId: String? = null,
    val messageId: String? = null
): BatchMessage() {}

@Serializable
sealed class EndpointNotification {
    @Serializable
    @SerialName("PushNotification")
    data class PushNotification(
        val channelType: String = "push-notification",
        val platform: String = "fcm",
        val token: String,
    ) : EndpointNotification()

    @Serializable
    @SerialName("EmailNotification")
    data class EmailNotification(
        val channelType: String = "email",
        val email: String,
    ) : EndpointNotification()
}

