package io.galva.network.request.communications

import kotlinx.serialization.Serializable

@Serializable
data class TrackCommunicationEventRequest(
    val communicationId: String,
    val eventType: String,
    val timestamp: String,
    val sentAt: String
) {
}