package io.galva.network.request.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("alias")
data class AliasMessage (
    val previousId:String,
    val targetId:String,
    val timestamp:String,
    val anonymousId:String? = null,
    @SerialName("context")
    val context: MessageContext?= null,
    val endUserId:String? = null,
    val messageId: String? = null
): BatchMessage(){
}