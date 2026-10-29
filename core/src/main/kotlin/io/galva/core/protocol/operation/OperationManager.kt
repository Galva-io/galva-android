package io.galva.core.protocol.operation

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface OperationManager {
    suspend fun recordOperation(apiOperation: APIOperation)

    suspend fun clearAllOperation()

    fun startRecordOperation()
    fun stopRecordOperation()

}
