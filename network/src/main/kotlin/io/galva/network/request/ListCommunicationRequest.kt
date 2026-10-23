package io.galva.network.request

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
class ListCommunicationRequest(
    val channelType: ChannelType,
    val anonymousId: String,
    val endUserId:String?,
    val cursor: String?,
    val limit: Int = 20
) {
}

@Serializable
@InternalGalvaApi
enum class ChannelType(val id: String) {
    @SerialName("in-app")
    IN_APP_MESSAGE("in-app"),

    @SerialName("push-notification")
    PUSH_NOTIFICATION("push-notification"),

    @SerialName("email")
    EMAIL("email")
}