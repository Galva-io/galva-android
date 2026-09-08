package io.galva.operation_queue.local

data class StoredOperation(
    val id: String,
    val type: String,
    val payload: String,// serialized JSON
    val createdAt: Long,
    val retryCount: Int = 0,
)