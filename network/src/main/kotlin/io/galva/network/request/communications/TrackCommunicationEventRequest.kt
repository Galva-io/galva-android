package io.galva.network.request.communications

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
data class TrackCommunicationEventRequest(
    val communicationId: String,
    val eventType: String,
    val timestamp: String,
    val sentAt: String
) {
}