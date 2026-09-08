package io.galva.billing.store

import io.galva.billing.model.Entitlement
import kotlinx.coroutines.flow.Flow

interface EntitlementStore {
    /** Snapshot of the current entitlement, or null if none. */
    suspend fun get(): Entitlement?

    /** Reactive entitlement updates. */
    fun observe(): Flow<Entitlement?>

    /** Persist a new entitlement (e.g., after successful purchase). */
    suspend fun save(entitlement: Entitlement)

    /** Clear when user logs out or entitlement expires. */
    suspend fun clear()
}