package io.galva.core.protocol.operation

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface OperationManager {
    suspend fun recordOperation(apiOperation: APIOperation)

    suspend fun clearAllOperation()

    /** Drops the pending operations recorded for [anonymousId]; operations of other identities are kept. */
    suspend fun clearOperationsOf(anonymousId: String)

    fun startRecordOperation()
    fun stopRecordOperation()

}
