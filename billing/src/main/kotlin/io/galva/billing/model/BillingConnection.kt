package io.galva.billing.model

interface BillingConnection {
    val isConnected: Boolean
    suspend fun ensureConnected()
    suspend fun ensureConnectedWithRetry()
    fun endConnection()
}