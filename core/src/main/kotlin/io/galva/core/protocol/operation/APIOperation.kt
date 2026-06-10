package io.galva.core.protocol.operation

import kotlinx.serialization.json.JsonObject

sealed class APIOperation {
    open val opType: String = "unknown"

    data class CreateAnonymousId(val anonymousId: String,val obfuscatedAccountId: String ) : APIOperation() {
        override val opType: String
            get() = "IdentityMessage"
    }

    data class IdentifyEmail(val anonymousId: String,val userId: String, val email: String) : APIOperation() {
        override val opType: String
            get() = "IdentityMessage"
    }

    data class Identify(val anonymousId: String,val userId: String,val obfuscatedAccountId: String?, val email:String?) : APIOperation() {
        override val opType: String
            get() = "IdentityMessage"
    }

    data class UpdateUserProperties(val anonymousId: String,val properties: JsonObject) : APIOperation() {
        override val opType: String
            get() = "IdentityMessage"
    }

    data class SetPushToken(val anonymousId: String,val token: String) : APIOperation() {
        override val opType: String
            get() = "CreateCommunicationEndpointMessage"
    }

    data class ClearPushToken(val anonymousId: String,val token: String) : APIOperation() {
        override val opType: String
            get() = "DeleteCommunicationEndpointMessage"
    }

}



