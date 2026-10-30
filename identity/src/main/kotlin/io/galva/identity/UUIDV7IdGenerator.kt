package io.galva.identity

import io.galva.common.InternalGalvaApi
import io.galva.common.utils.UUIDv7
import kotlin.uuid.ExperimentalUuidApi

@InternalGalvaApi
class UUIDV7IdGenerator : AnonymousIdGenerator {
    @OptIn(ExperimentalUuidApi::class)
    override fun newAnonymousId(): String {
        return UUIDv7.randomUUID().toString()
    }

    override fun newObfuscatedAccountId(): String {
        return UUIDv7.randomUUID().toString()
    }
}