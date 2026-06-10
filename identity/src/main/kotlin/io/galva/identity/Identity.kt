package io.galva.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Identity @JvmOverloads constructor(
    val anonymousId: String,
    val obfuscatedAccountId: String,
    val userId: String? = null,
    val email: String? = null,
    val properties: JsonObject = JsonObject(emptyMap()),
    val firstCreated : Boolean,
    val pushToken:String? = null
) {
    val isIdentified: Boolean get() = userId != null
    val isAnonymous: Boolean get() = !isIdentified

    fun mergeProperties(extra: JsonObject): Identity =
        copy(properties = JsonObject(properties + extra))

    fun setProperty(key: String, value: JsonElement): Identity =
        copy(properties = JsonObject(properties + (key to value)))

    fun isEmpty() = anonymousId.isEmpty()

    companion object {
        @JvmStatic
        fun anonymous(anonymousId: String, obfuscatedAccountId: String, firstCreated : Boolean = true ): Identity = Identity(anonymousId = anonymousId,obfuscatedAccountId=obfuscatedAccountId, firstCreated = firstCreated)

        fun empty() = Identity(anonymousId = "", obfuscatedAccountId = "", firstCreated = false)

    }
}