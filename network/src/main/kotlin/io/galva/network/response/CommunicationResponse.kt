package io.galva.network.response

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
data class CommunicationResponse(
    val data: List<Communication>,
    val success: Boolean,
    val meta: Meta
)

@Serializable
@InternalGalvaApi
data class Communication(
    val id: String,
    val type: String,
    val workflowType: String?,
    val createdAt: String
) {
}

@Serializable
@InternalGalvaApi
data class Meta(
    val nextCursor: String?
)