package io.galva.identity

import io.galva.common.InternalGalvaApi

@InternalGalvaApi
interface AnonymousIdGenerator {
    fun newAnonymousId(): String

    fun newObfuscatedAccountId(): String
}