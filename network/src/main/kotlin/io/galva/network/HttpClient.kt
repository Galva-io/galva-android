package io.galva.network

interface HttpClient {
    suspend fun execute(request: HttpRequest): HttpResponse
}