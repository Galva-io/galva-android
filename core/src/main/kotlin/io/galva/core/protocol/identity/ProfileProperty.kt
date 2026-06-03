package io.galva.core.protocol.identity

import io.galva.common.utils.JsonUtils
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

sealed class ProfileProperty(val key: String, val propertyValue: JsonElement) {
    data class Email(val email: String) : ProfileProperty("email", JsonPrimitive(email))
    data class Custom(val propertyKey:String ,val value: Any) :
        ProfileProperty(propertyKey, JsonUtils.convertObjectToJsonElement(value))
}
