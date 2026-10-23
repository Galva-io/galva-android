package io.galva.operation_queue.local

data class StoredOperation(
    val id: String,
    val type: String,
    val payload: String,// serialized JSON
    val createdAt: Long,
    val retryCount: Int = 0,
    /** Identity (anonymous id) the operation belongs to; null for identity-independent operations. */
    val ownerId: String? = null,
)
