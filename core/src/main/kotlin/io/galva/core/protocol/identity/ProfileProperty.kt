package io.galva.core.protocol.identity

import io.galva.common.utils.JsonUtils
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

sealed class ProfileProperty(val key: String, val propertyValue: JsonElement) {
    data class Email(val email: String) : ProfileProperty( $$"$gv_email", JsonPrimitive(email))
    data class FirstName(val firstName: String) : ProfileProperty( $$"$gv_firstName", JsonPrimitive(firstName))
    data class LastName(val lastName: String) : ProfileProperty( $$"$gv_lastName", JsonPrimitive(lastName))
    data class FullName(val lastName: String) : ProfileProperty( $$"$gv_fullName", JsonPrimitive(lastName))
    data class LastActiveTime(val lastActiveTime: Long) : ProfileProperty( $$"$gv_lastSeenAt", JsonPrimitive(lastActiveTime))
    data class Custom(val propertyKey:String ,val value: Any) :
        ProfileProperty(propertyKey, JsonUtils.convertObjectToJsonElement(value))
}
