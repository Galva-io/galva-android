package io.galva.sdk

import io.galva.common.utils.JsonUtils
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** A user profile property for [Galva.updateProperties]: a reserved Galva trait, or [Custom]. */
public sealed class ProfileProperty(public val key: String) {
    public data class Email(val email: String) : ProfileProperty($$"$gv_email")
    public data class FirstName(val firstName: String) : ProfileProperty($$"$gv_firstName")
    public data class LastName(val lastName: String) : ProfileProperty($$"$gv_lastName")
    public data class FullName(val fullName: String) : ProfileProperty($$"$gv_fullName")
    public data class LastActiveTime(val lastActiveTime: Long) : ProfileProperty($$"$gv_lastSeenAt")

    /** [value] is a string, number, boolean, or a list / map of those. */
    public data class Custom(val propertyKey: String, val value: Any) : ProfileProperty(propertyKey)
}

/** The value as sent to Galva. Converted on the SDK's thread, not the caller's. */
internal fun ProfileProperty.toJson(): JsonElement = when (this) {
    is ProfileProperty.Email -> JsonPrimitive(email)
    is ProfileProperty.FirstName -> JsonPrimitive(firstName)
    is ProfileProperty.LastName -> JsonPrimitive(lastName)
    is ProfileProperty.FullName -> JsonPrimitive(fullName)
    is ProfileProperty.LastActiveTime -> JsonPrimitive(lastActiveTime)
    is ProfileProperty.Custom -> JsonUtils.convertObjectToJsonElement(value)
}
