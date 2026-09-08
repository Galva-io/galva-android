package io.galva.network.service

import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult

interface IAMService {
    suspend fun apiFetch(apiFetchRequest: APIFetchRequest): ServiceResult<APIFetchResult>
}