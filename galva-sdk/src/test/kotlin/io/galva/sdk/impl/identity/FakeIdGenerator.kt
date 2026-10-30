package io.galva.sdk.impl.identity

class FakeIdGenerator: io.galva.identity.AnonymousIdGenerator {
    private var counter = 0

    override fun newAnonymousId(): String {
        return "anon_${counter++}"
    }

    override fun newObfuscatedAccountId(): String {
        return "account_${counter++}"
    }
}