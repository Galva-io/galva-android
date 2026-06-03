package io.galva.network.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ListCommunicationRequest(
    val channelType: ChannelType,
    val anonymousId: String,
    val endUserId:String?,
    val cursor: String?,
    val limit: Int = 20
) {
}

@Serializable
enum class ChannelType(val id: String) {
    @SerialName("in-app")
    IN_APP_MESSAGE("in-app"),

    @SerialName("push-notification")
    PUSH_NOTIFICATION("push-notification"),

    @SerialName("email")
    EMAIL("email")
}