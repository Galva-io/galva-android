package io.galva.billing.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.galva.billing.model.Entitlement
import io.galva.billing.model.EntitlementState
import io.galva.billing.store.EntitlementStore
import io.galva.common.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

class SqliteEntitlementStore(
    private val helper: BillingSqliteHelper,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): EntitlementStore {
    private val invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override suspend fun get(): Entitlement? = withContext(ioDispatcher) {
        val db = helper.readableDatabase
        db.rawQuery(
            """SELECT product_id, purchase_token, order_id,
                      acquired_at_ms, is_auto_renewing, state
               FROM entitlement WHERE id = 1 LIMIT 1""",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) readEntitlement(cursor) else null
        }
    }

    override fun observe(): Flow<Entitlement?> = invalidations
        .onStart { emit(Unit) }
        .mapLatest { get() }
        .distinctUntilChanged()

    override suspend fun save(entitlement: Entitlement): Unit = withContext(ioDispatcher) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("id", 1)
            put("product_id", entitlement.productId)
            put("purchase_token", entitlement.purchaseToken)
            if (entitlement.orderId != null) put("order_id", entitlement.orderId) else putNull("order_id")
            put("acquired_at_ms", entitlement.acquiredAtMs)
            put("is_auto_renewing", if (entitlement.isAutoRenewing) 1 else 0)
            put("state", entitlement.state.name)
            put("updated_at_ms", System.currentTimeMillis())
        }
        db.insertWithOnConflict("entitlement", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        invalidations.tryEmit(Unit)
        logger.info { "Saved entitlement: ${entitlement.productId} (state=${entitlement.state})" }
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        val db = helper.writableDatabase
        db.execSQL("DELETE FROM entitlement")
        invalidations.tryEmit(Unit)
        logger.info { "Cleared entitlement" }
    }

    private fun readEntitlement(cursor: android.database.Cursor): Entitlement = Entitlement(
        productId = cursor.getString(0),
        purchaseToken = cursor.getString(1),
        orderId = if (cursor.isNull(2)) null else cursor.getString(2),
        acquiredAtMs = cursor.getLong(3),
        isAutoRenewing = cursor.getInt(4) == 1,
        state = EntitlementState.valueOf(cursor.getString(5)),
    )
}