package io.galva.identity

import io.galva.common.InternalGalvaApi
import io.galva.localstorage.core.KeyValueStorage
import io.galva.localstorage.core.getObject
import io.galva.localstorage.core.putObject

@InternalGalvaApi
interface IdentityStore {
   suspend fun load(): Identity?
    suspend fun save(identity: Identity)
}

@InternalGalvaApi
class LocalStorageIdentityStore(private val keyValueStorage: KeyValueStorage) : IdentityStore {
    override suspend fun load(): Identity? {
        return keyValueStorage.getObject(KEY_USER)
    }

    override suspend fun save(identity: Identity) {
        keyValueStorage.putObject(KEY_USER, identity)
    }

    private companion object {
        const val KEY_USER = "user"             // in secure storage
    }
}