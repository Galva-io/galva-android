package io.galva.network.service

import io.galva.common.InternalGalvaApi
import io.galva.network.request.APIFetchRequest
import io.galva.network.response.APIFetchResult

@InternalGalvaApi
interface IAMService {
    /** True when [path] resolves to a URL on the API origin (relative paths only). */
    fun isValidPath(path: String): Boolean

    /**
     * Sends the request once. Any completed HTTP exchange — 4xx / 5xx included — is a
     * [ServiceResult.Success]; transport failures are [ServiceResult.Error].
     */
    suspend fun apiFetch(apiFetchRequest: APIFetchRequest): ServiceResult<APIFetchResult>
}
