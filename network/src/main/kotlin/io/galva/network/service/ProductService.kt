package io.galva.network.service

import io.galva.network.request.products.ListProductRequest
import io.galva.network.response.ListProductResponse

interface ProductService {

    suspend fun getProducts(request: ListProductRequest): ServiceResult<ListProductResponse>
}