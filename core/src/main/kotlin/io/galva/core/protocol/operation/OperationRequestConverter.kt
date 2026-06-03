package io.galva.core.protocol.operation

import io.galva.network.request.messages.BatchMessage

interface OperationRequestConverter {
    fun operationToBatchMessage(operation: APIOperation): BatchMessage
}

interface AdvertingProvider {
    fun adTrackingEnabled(): Boolean
    fun advertingId(): String?
}