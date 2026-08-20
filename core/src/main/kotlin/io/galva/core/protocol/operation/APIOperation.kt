package io.galva.core.protocol.operation

import kotlinx.serialization.json.JsonObject

sealed class APIOperation {
    open val opType: String = "unknown"
    companion object{
        const val IDENTITY_MESSAGE = "IdentityMessage"
        const val CREATE_COMMUNICATION_ENDPOINT_MESSAGE = "CreateCommunicationEndpointMessage"
        const val DELETE_COMMUNICATION_ENDPOINT_MESSAGE = "DeleteCommunicationEndpointMessage"
        const val TRACK_PUSH_NOTIFICATION_MESSAGE = "TrackPushNotification"

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

    data class SetPushToken(val anonymousId: String,val token: String) : APIOperation() {
        override val opType: String
            get() = CREATE_COMMUNICATION_ENDPOINT_MESSAGE
    }

    data class ClearPushToken(val anonymousId: String,val token: String) : APIOperation() {
        override val opType: String
            get() = DELETE_COMMUNICATION_ENDPOINT_MESSAGE
    }
    data class TrackPushNotification(val communicationId: String,val eventType: String,val timestamp:String ) : APIOperation() {
        override val opType: String
            get() = TRACK_PUSH_NOTIFICATION_MESSAGE
    }

}



