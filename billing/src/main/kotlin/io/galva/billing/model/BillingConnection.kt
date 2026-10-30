package io.galva.billing.model

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface BillingConnection {
    val isConnected: Boolean
    suspend fun ensureConnected()
    suspend fun ensureConnectedWithRetry()
    fun endConnection()
}