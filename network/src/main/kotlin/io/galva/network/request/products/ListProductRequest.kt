package io.galva.network.request.products

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
data class ListProductRequest(
    val status: String = Status.ACTIVE.id,
    val page: Int = 1, val limit: Int = 20
) {}
@InternalGalvaApi
enum class Status(val id:String ){

    ACTIVE("active" ),
    ARCHIVED("archived"),
    DELETED("deleted")
}