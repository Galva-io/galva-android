package io.galva.billing.model

import kotlinx.serialization.Serializable

@Serializable
data class Offer(
    val offerToken: String,                   // primary key — opaque token from Play
    val offerId: String? = null,              // human-readable id (Play Console offer id)
    val basePlanId: String,                   // FK → BasePlan.id
    val tags: List<String> = emptyList()
)