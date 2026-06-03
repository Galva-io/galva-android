package io.galva.operation_queue.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.galva.common.logger.Logger
import io.galva.operation_queue.OperationStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

class SqliteOperationStore(
    private val helper: OperationsSqliteHelper,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): OperationStore {
    private val invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    // ── Reads ───────────────────────────────────────────────────────────────

    override fun pendingCount(): Flow<Int> = invalidations
        .onStart { emit(Unit) }
        .mapLatest { withContext(ioDispatcher) { countPendingInternal() } }
        .distinctUntilChanged()

    /** Count rows that are unlocked — the consumer's gate. */
    private fun countPendingInternal(): Int {
        val db = helper.readableDatabase
        return db.rawQuery(
            "SELECT COUNT(*) FROM operations WHERE lock_token IS NULL",
            null,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    override suspend fun insert(op: StoredOperation) = withContext(ioDispatcher) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("id", op.id)
            put("type", op.type)
            put("payload", op.payload)
            put("created_at", op.createdAt)
            put("retry_count", op.retryCount)
            putNull("lock_token")
            putNull("locked_at")
        }
        // CONFLICT_IGNORE — duplicate ids are skipped, matching `OnConflictStrategy.IGNORE`
        db.insertWithOnConflict("operations", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        invalidations.tryEmit(Unit)
        logger.verbose { "Inserted ${op.id} (type=${op.type})" }
    }

    override suspend fun claimBatch(
        limit: Int,
        lockToken: String,
        now: Long,
    ): List<StoredOperation> = withContext(ioDispatcher) {
        val db = helper.writableDatabase

        db.beginTransaction()
        try {
            // Select unlocked candidates, oldest first
            val candidates = mutableListOf<StoredOperation>()
            db.rawQuery(
                """SELECT id, type, payload, created_at, retry_count FROM operations
                   WHERE lock_token IS NULL
                   ORDER BY created_at ASC LIMIT ?""",
                arrayOf(limit.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    candidates.add(
                        StoredOperation(
                            id = cursor.getString(0),
                            type = cursor.getString(1),
                            payload = cursor.getString(2),
                            createdAt = cursor.getLong(3),
                            retryCount = cursor.getInt(4),
                        )
                    )
                }
            }

            if (candidates.isEmpty()) {
                db.setTransactionSuccessful()
                return@withContext emptyList()
            }

            // Lock claimed rows
            val ids = candidates.map { it.id }
            val placeholders = ids.joinToString(",") { "?" }
            val args = buildList {
                add(lockToken)
                add(now)
                addAll(ids)
            }
            db.execSQL(
                """UPDATE operations SET lock_token = ?, locked_at = ?
                   WHERE id IN ($placeholders)""",
                args.toTypedArray<Any>()
            )

            db.setTransactionSuccessful()
            invalidations.tryEmit(Unit)                                // pending count went down
            logger.debug { "Claimed batch of ${candidates.size} with token $lockToken" }
            candidates
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun delete(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext
        val db = helper.writableDatabase

        // SQLite caps host parameters at 999; chunk to be safe
        db.beginTransaction()
        try {
            ids.chunked(CHUNK_SIZE).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "DELETE FROM operations WHERE id IN ($placeholders)",
                    chunk.toTypedArray<Any>(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        logger.debug { "Deleted ${ids.size} operations" }
        // No invalidation: deleted rows were locked, so pending count didn't change
    }

    override suspend fun release(ids: List<String>): Unit = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext
        val db = helper.writableDatabase

        db.beginTransaction()
        try {
            ids.chunked(CHUNK_SIZE).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    """UPDATE operations SET
                         lock_token = NULL,
                         locked_at = NULL,
                         retry_count = retry_count + 1
                       WHERE id IN ($placeholders)""",
                    chunk.toTypedArray<Any>(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        invalidations.tryEmit(Unit)                                    // pending count went up
        logger.debug { "Released lock for ${ids.size} operations" }
    }

    override suspend fun releaseAllLocks(): Unit = withContext(ioDispatcher) {
        val db = helper.writableDatabase
        db.execSQL(
            """UPDATE operations SET
                 lock_token = NULL,
                 locked_at = NULL
               WHERE lock_token IS NOT NULL"""
        )
        invalidations.tryEmit(Unit)                                    // all locked rows become pending
        logger.debug { "Released all locks (recovery from prior crashed process)" }
    }

    override suspend fun clearAll(): Unit = withContext(ioDispatcher) {
        val db = helper.writableDatabase
        db.execSQL("DELETE FROM operations")
        invalidations.tryEmit(Unit)
        logger.debug { "Cleared all operations" }
    }

    private companion object {
        // SQLite caps host parameters at 999; chunk to be safe
        const val CHUNK_SIZE = 300
    }
}