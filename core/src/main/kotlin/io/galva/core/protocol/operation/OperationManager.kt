package io.galva.core.protocol.operation

interface OperationManager {
    suspend fun recordOperation(apiOperation: APIOperation)

    suspend fun clearAllOperation()

    fun startRecordOperation()
    fun stopRecordOperation()

}
