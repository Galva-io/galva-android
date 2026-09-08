package io.galva.identity

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

interface IdentityManager {
    val state: StateFlow<Identity>
    val current: Identity get() = state.value

    val anonymousId: String get() = current.anonymousId
    val userId: String? get() = current.userId

    val obfuscatedAccountId: String get() = current.obfuscatedAccountId

    suspend fun initialize()

    suspend fun awaitInitialized()

    suspend fun identify(userId: String,email:String?, obfuscatedAccountId:String? )

    suspend fun updateUserProperties(properties: JsonObject)

    suspend fun setPushToken(token: String)

    suspend fun clearPushToken()

    suspend fun logout()
}