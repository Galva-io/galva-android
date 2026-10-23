package io.galva.network.request.messages

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("set-communication-preference")
@InternalGalvaApi
data class SetCommunicationPreferenceMessage(
    val channelType: ChannelType,
    val timestamp: String,
    val anonymousId: String? = null,
    @SerialName("context")
    val context: MessageContext? = null,
    val disabled: Boolean ? = null,
    val endUserId: String? = null,
    val messageId: String? = null,
): BatchMessage() {}

@Serializable
@InternalGalvaApi
enum class ChannelType{
    @SerialName("email")
    Email,
    @SerialName("push-notification")
    PushNotification,
    @SerialName("in-app")
    InApp
}