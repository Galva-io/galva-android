package io.galva.network.request

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
data class ResolveMessageRequest(
    val messageId: String,
    val anonymousId: String,
    val bridgeProtocolVersion: String?,
    val countryCode: String,
    val endUserId:String?
)