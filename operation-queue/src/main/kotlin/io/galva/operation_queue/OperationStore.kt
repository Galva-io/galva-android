package io.galva.operation_queue

import io.galva.common.InternalGalvaApi
import io.galva.operation_queue.local.StoredOperation
import kotlinx.coroutines.flow.Flow

@InternalGalvaApi
interface OperationStore {
    /** Atomic insert; ignores duplicates by id */
    suspend fun insert(op: StoredOperation)

    /** Atomically claim up to [limit] unlocked rows; returns claimed rows. */
    suspend fun claimBatch(limit: Int, lockToken: String, now: Long): List<StoredOperation>

    /** Delete rows after successful send. */
    suspend fun delete(ids: List<String>)

    /** Release lock and bump retry count after failure. */
    suspend fun release(ids: List<String>)

    /** Clear stale locks left by a prior crashed process. */
    suspend fun releaseAllLocks()

    /** Live count of unlocked pending rows; drives the consumer. */
    fun pendingCount(): Flow<Int>

    /** remove all operations **/
    suspend fun clearAll()

    /** Remove every operation owned by [ownerId], including rows currently claimed by the consumer. */
    suspend fun clearOwnedBy(ownerId: String)

    /** Delete the oldest rows so at most [maxCount] remain; returns how many were deleted. */
    suspend fun trimToNewest(maxCount: Int): Int
}
