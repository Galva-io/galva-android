package io.galva.network.service

import io.galva.common.InternalGalvaApi
import io.galva.network.request.products.ListProductRequest
import io.galva.network.response.ListProductResponse

@InternalGalvaApi
interface ProductService {

    suspend fun getProducts(request: ListProductRequest): ServiceResult<ListProductResponse>
}