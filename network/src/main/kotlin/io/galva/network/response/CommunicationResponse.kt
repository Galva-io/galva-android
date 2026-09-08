package io.galva.network.response

import kotlinx.serialization.Serializable

@Serializable
data class CommunicationResponse(
    val data: List<Communication>,
    val success: Boolean,
    val meta: Meta
)

@Serializable
data class Communication(
    val id: String,
    val type: String,
    val workflowType: String?,
    val createdAt: String
) {
}

@Serializable
data class Meta(
    val nextCursor: String?
)