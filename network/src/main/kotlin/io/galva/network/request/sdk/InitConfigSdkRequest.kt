package io.galva.network.request.sdk

import kotlinx.serialization.Serializable

@Serializable
data class InitConfigSdkRequest(
    val bridgeProtocolVersion: String
)