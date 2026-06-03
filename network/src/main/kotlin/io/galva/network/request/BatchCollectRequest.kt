package io.galva.network.request

import io.galva.network.request.messages.BatchMessage
import kotlinx.serialization.Serializable

@Serializable
data class BatchCollectRequest(
    val messages: List<BatchMessage>,
    val sentAt: String,

    )