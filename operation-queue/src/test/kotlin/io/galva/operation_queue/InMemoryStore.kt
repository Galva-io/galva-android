package io.galva.operation_queue

import io.galva.operation_queue.local.StoredOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryStore : OperationStore {
    private val rows = linkedMapOf<String, StoredOperation>()
    private val locks = mutableMapOf<String, String>()
    private val count = MutableStateFlow(0)
    private fun refresh() {
        count.value = rows.keys.count { it !in locks }
    }

    override suspend fun insert(op: StoredOperation) {
        if (op.id !in rows) {
            rows[op.id] = op;
            refresh()
        }
    }

    override suspend fun claimBatch(limit: Int, lockToken: String, now: Long) =
        rows.values.filter { it.id !in locks }.sortedBy { it.createdAt }.take(limit)
            .also { batch ->
                batch.forEach { locks[it.id] = lockToken };
                refresh()
            }

    override suspend fun delete(ids: List<String>) {
        ids.forEach { rows.remove(it); locks.remove(it) }; refresh()
    }

    override suspend fun release(ids: List<String>) {
        ids.forEach { id ->
            locks.remove(id)
            rows[id]?.let { rows[id] = it.copy(retryCount = it.retryCount + 1) }
        }; refresh()
    }

    override suspend fun releaseAllLocks() {
        locks.clear(); refresh()
    }

    override fun pendingCount(): Flow<Int> = count.asStateFlow()

    fun rowCount() = rows.size
    fun rowsByCreated() = rows.values.sortedBy { it.createdAt }
    fun retryCountOf(id: String) = rows[id]?.retryCount ?: 0
    fun seed(op: StoredOperation, locked: Boolean = false) {
        rows[op.id] = op
        if (locked) locks[op.id] = "stale-token"
        refresh()
    }

    override suspend fun clearAll() {
        locks.clear()
        rows.clear()
        refresh()
    }
}