package io.galva.network.response

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
class ListProductResponse(
    val data: List<ProductResponse>,
    val meta: MetaTotal,
    val isSuccess: Boolean
) {}

@Serializable
@InternalGalvaApi
data class ProductResponse(val id: String)

@Serializable
@InternalGalvaApi
data class MetaTotal(val total: Int?)