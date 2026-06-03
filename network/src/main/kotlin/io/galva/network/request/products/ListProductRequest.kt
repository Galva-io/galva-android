package io.galva.network.request.products

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListProductRequest(
    val status: String = Status.ACTIVE.id,
    val page: Int = 1, val limit: Int = 20
) {}
enum class Status(val id:String ){

    ACTIVE("active" ),
    ARCHIVED("archived"),
    DELETED("deleted")
}