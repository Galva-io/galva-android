package io.galva.network.service

import io.galva.network.request.BatchCollectRequest
import io.galva.network.request.ListCommunicationRequest
import io.galva.network.request.ResolveMessageRequest
import io.galva.network.response.CommunicationResponse
import io.galva.network.response.MessageResponse

interface IdentifyService {
    suspend fun sendBatchCollectRequest(request: BatchCollectRequest): ServiceResult<Unit>
    suspend fun fetchCommunications(request: ListCommunicationRequest): ServiceResult<CommunicationResponse>
    suspend fun resolveMessage(request: ResolveMessageRequest): ServiceResult<MessageResponse>
}