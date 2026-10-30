package io.galva.common.utils

import io.galva.common.InternalGalvaApi

/**
 * Basic RFC 5322 shape check, same rules as the iOS SDK: exactly one `@`, a non-empty local
 * part and a dotted domain without empty labels, no whitespace, and only characters allowed in
 * each part. Invalid addresses are dropped before they reach the backend.
 */
@InternalGalvaApi
object EmailValidator {
    private const val LOCAL_SPECIALS = ".!#$%&'*+/=?^_`{|}~-"
    private const val DOMAIN_SPECIALS = "-."

    fun isValid(email: String): Boolean {
        if (email.isEmpty() || email.any { it.isWhitespace() }) return false
        val parts = email.split("@")
        if (parts.size != 2) return false
        val (local, domain) = parts
        if (local.isEmpty() || domain.isEmpty() || "." !in domain) return false
        if (domain.split(".").any { it.isEmpty() }) return false
        return local.all { it.isLetterOrDigit() || it in LOCAL_SPECIALS } &&
            domain.all { it.isLetterOrDigit() || it in DOMAIN_SPECIALS }
    }
}
