package io.galva.billing.model

import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val sku: String,
    val title: String,
    val description: String,
    val type: ProductType,
)

@Serializable
enum class ProductType { SUBSCRIPTION, ONE_TIME }