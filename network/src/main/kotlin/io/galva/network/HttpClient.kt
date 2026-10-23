package io.galva.network

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface HttpClient {
    suspend fun execute(request: HttpRequest): HttpResponse
}