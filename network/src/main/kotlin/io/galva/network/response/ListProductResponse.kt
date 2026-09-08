package io.galva.network.response

import kotlinx.serialization.Serializable

@Serializable
class ListProductResponse(
    val data: List<ProductResponse>,
    val meta: MetaTotal,
    val isSuccess: Boolean
) {}

@Serializable
data class ProductResponse(val id: String)

@Serializable
data class MetaTotal(val total: Int?)