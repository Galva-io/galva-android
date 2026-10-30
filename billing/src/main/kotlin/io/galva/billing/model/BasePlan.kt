package io.galva.billing.model

import kotlinx.serialization.Serializable

@Serializable
data class BasePlan(
    val id: String,                           // base plan id from Play Console
    val productSku: String,                   // FK → Product.sku
    val tags: List<String> = emptyList(),
)