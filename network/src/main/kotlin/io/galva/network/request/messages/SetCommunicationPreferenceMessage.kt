package io.galva.network.request.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("SetCommunicationPreferenceMessage")
data class SetCommunicationPreferenceMessage(
    val channelType: ChannelType,
    val timestamp: String,
    @SerialName("type")
    val type: String ="set-communication-preference",
    val anonymousId: String? = null,
    @SerialName("context")
    val context: MessageContext? = null,
    val disabled: Boolean ? = null,
    val endUserId: String? = null,
    val messageId: String? = null,
): BatchMessage() {}

@Serializable
enum class ChannelType{
    @SerialName("email")
    Email,
    @SerialName("push-notification")
    PushNotification,
    @SerialName("in-app")
    InApp
}