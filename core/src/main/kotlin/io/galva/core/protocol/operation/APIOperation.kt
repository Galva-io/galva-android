package io.galva.core.protocol.operation

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.json.JsonObject

/**
 * One call to send, carrying the identity it was made as. It is sent as recorded, even after a
 * logout or identify switched the current user.
 */
@InternalGalvaApi
sealed class APIOperation {
    open val opType: String = "unknown"

    companion object{
        const val IDENTITY_MESSAGE = "IdentityMessage"
        const val CREATE_COMMUNICATION_ENDPOINT_MESSAGE = "CreateCommunicationEndpointMessage"
        const val DELETE_COMMUNICATION_ENDPOINT_MESSAGE = "DeleteCommunicationEndpointMessage"
        const val TRACK_MESSAGE = "TrackMessage"

    }

    data class CreateAnonymousId(val anonymousId: String,val obfuscatedAccountId: String ) : APIOperation() {
        override val opType: String
            get() = IDENTITY_MESSAGE
    }
    data class Identify(val anonymousId: String,val userId: String,val obfuscatedAccountId: String?, val email:String?) : APIOperation() {
        override val opType: String
            get() = IDENTITY_MESSAGE
    }

    data class UpdateUserProperties(val anonymousId: String,val properties: JsonObject) : APIOperation() {
        override val opType: String
            get() = IDENTITY_MESSAGE
    }

    /** [platform] is the push service that issued [token]: `fcm`, `hms` or `adm`. */
    data class SetPushToken(val anonymousId: String,val token: String, val platform: String) : APIOperation() {
        override val opType: String
            get() = CREATE_COMMUNICATION_ENDPOINT_MESSAGE
    }

    data class ClearPushToken(val anonymousId: String,val token: String, val platform: String) : APIOperation() {
        override val opType: String
            get() = DELETE_COMMUNICATION_ENDPOINT_MESSAGE
    }
    data class Track(
        val anonymousId: String,
        val userId: String?,
        val event: String,
        val properties: JsonObject?,
    ) : APIOperation() {
        override val opType: String
            get() = TRACK_MESSAGE
    }

}
