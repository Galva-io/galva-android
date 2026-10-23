package io.galva.network.request.sdk

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
data class InitConfigSdkRequest(
    val bridgeProtocolVersion: String
)