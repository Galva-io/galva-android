package io.galva.operation_queue

interface OperationQueue {
    fun start()
    suspend fun enqueue(payload: String, type: String, id: String? = null)
    fun flush()
    fun stop()

    suspend fun clear()

}