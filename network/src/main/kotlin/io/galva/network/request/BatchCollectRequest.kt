package io.galva.network.request

import io.galva.common.InternalGalvaApi
import io.galva.network.request.messages.BatchMessage
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
data class BatchCollectRequest(
    val messages: List<BatchMessage>,
    val sentAt: String,

    )