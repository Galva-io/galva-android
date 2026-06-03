package io.galva.identity

interface AnonymousIdGenerator {
    fun newAnonymousId(): String

    fun newObfuscatedAccountId(): String
}