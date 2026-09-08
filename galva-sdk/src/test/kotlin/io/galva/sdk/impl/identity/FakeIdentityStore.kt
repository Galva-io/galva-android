package io.galva.sdk.impl.identity

class FakeIdentityStore: io.galva.identity.IdentityStore {

    var value: io.galva.identity.Identity? = null

    override suspend fun load(): io.galva.identity.Identity? = value

    override suspend fun save(identity: io.galva.identity.Identity) {
        value = identity
    }
}