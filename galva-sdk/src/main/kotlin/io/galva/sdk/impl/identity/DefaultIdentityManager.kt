package io.galva.sdk.impl.identity

import android.content.Context
import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.identity.AnonymousIdGenerator
import io.galva.identity.Identity
import io.galva.identity.IdentityManager
import io.galva.identity.IdentityStore
import io.galva.identity.LocalStorageIdentityStore
import io.galva.identity.UUIDV7IdGenerator
import io.galva.localstorage.KeyValueStorageFactory
import io.galva.localstorage.core.KeyValueStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

class DefaultIdentityManager(
    private val store: IdentityStore,
    private val idGenerator: AnonymousIdGenerator,
    private val logger: Logger = NoOpLogger,
) : IdentityManager {
    private val initializedSignal = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow(Identity.empty())
    override val state: StateFlow<Identity> = _state.asStateFlow()

    override suspend fun initialize() {
        if (initializedSignal.isCompleted) return
        val userCached = store.load()
        if (userCached == null) {
            val newIdentity = Identity.anonymous(
                idGenerator.newAnonymousId(), idGenerator.newObfuscatedAccountId()
            )
            _state.value = newIdentity
            store.save(newIdentity)
        } else {
            _state.value = userCached
        }
        initializedSignal.complete(Unit)
    }

    override suspend fun awaitInitialized() {
        initializedSignal.await()
    }

    override suspend fun identify(
        userId: String, email: String?, obfuscatedAccountId: String?
    ) {
        update {
            it.copy(
                userId = userId,
                email = email ?: it.email,
                obfuscatedAccountId = obfuscatedAccountId ?: it.obfuscatedAccountId
            )
        }
    }

    private suspend fun update(block: (Identity) -> Identity) {
        logger.debug { "Updating start awaitInitialized" }
        awaitInitialized()
        val newState = block(_state.value)
        logger.debug {
            "awaitInitialized done: newState $newState"
        }
        _state.value = newState
        store.save(newState)
    }

    override suspend fun updateUserProperties(properties: JsonObject) {
        update {
            it.mergeProperties(properties)
        }
    }


    override suspend fun logout() {
        update {
            Identity(
                anonymousId = idGenerator.newAnonymousId(),
                firstCreated = true,
                obfuscatedAccountId = idGenerator.newObfuscatedAccountId()
            )
        }
    }

    companion object {
        fun create(localKeyValueStorage: KeyValueStorage, logger: Logger): IdentityManager {
            val storage = LocalStorageIdentityStore(localKeyValueStorage)
            val anonymousIdGenerator: AnonymousIdGenerator = UUIDV7IdGenerator()
            return DefaultIdentityManager(storage, anonymousIdGenerator, logger)
        }
    }
}