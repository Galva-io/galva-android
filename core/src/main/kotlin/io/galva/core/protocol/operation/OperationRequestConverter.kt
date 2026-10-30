package io.galva.core.protocol.operation

import io.galva.common.InternalGalvaApi
import io.galva.network.request.messages.BatchMessage

@InternalGalvaApi
interface OperationRequestConverter {
    fun operationToBatchMessage(operation: APIOperation): BatchMessage
}

@InternalGalvaApi
interface AdvertisingProvider {
    fun adTrackingEnabled(): Boolean
    fun advertisingId(): String?
}