package io.galva.core.protocol.operation

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.json.JsonObject

@InternalGalvaApi
sealed class APIOperation {
    open val opType: String = "unknown"

    /**
     * Anonymous id of the identity this operation belongs to. Used to purge a signed-out
     * user's pending operations on logout; null for operations not tied to an identity.
     */
    open val ownerId: String? = null

    companion object{
        const val IDENTITY_MESSAGE = "IdentityMessage"
        const val CREATE_COMMUNICATION_ENDPOINT_MESSAGE = "CreateCommunicationEndpointMessage"
        const val DELETE_COMMUNICATION_ENDPOINT_MESSAGE = "DeleteCommunicationEndpointMessage"
        const val TRACK_MESSAGE = "TrackMessage"

    }

    data class CreateAnonymousId(val anonymousId: String,val obfuscatedAccountId: String ) : APIOperation() {
        override val opType: String
            get() = IDENTITY_MESSAGE
        override val ownerId: String
            get() = anonymousId
    }
    data class Identify(val anonymousId: String,val userId: String,val obfuscatedAccountId: String?, val email:String?) : APIOperation() {
        override val opType: String
            get() = IDENTITY_MESSAGE
        override val ownerId: String
            get() = anonymousId
    }

    data class UpdateUserProperties(val anonymousId: String,val properties: JsonObject) : APIOperation() {
        override val opType: String
            get() = IDENTITY_MESSAGE
        override val ownerId: String
            get() = anonymousId
    }

    /** [platform] is the push service that issued [token]: `fcm`, `hms` or `adm`. */
    data class SetPushToken(val anonymousId: String,val token: String, val platform: String) : APIOperation() {
        override val opType: String
            get() = CREATE_COMMUNICATION_ENDPOINT_MESSAGE
        override val ownerId: String
            get() = anonymousId
    }

    data class ClearPushToken(val anonymousId: String,val token: String, val platform: String) : APIOperation() {
        override val opType: String
            get() = DELETE_COMMUNICATION_ENDPOINT_MESSAGE
        override val ownerId: String
            get() = anonymousId
    }
    data class Track(
        val anonymousId: String,
        val userId: String?,
        val event: String,
        val properties: JsonObject?,
    ) : APIOperation() {
        override val opType: String
            get() = TRACK_MESSAGE
        override val ownerId: String
            get() = anonymousId
    }

}
