package io.galva.common.utils

import io.galva.common.InternalGalvaApi

/**
 * Play Billing's rules for `obfuscatedAccountId`, which the SDK attaches to purchases as is: not
 * blank, at most 64 characters, and no personal data in cleartext (Play blocks purchases that
 * carry an email address). Invalid values are dropped before they are stored or attached.
 */
@InternalGalvaApi
object ObfuscatedAccountIdValidator {
    const val MAX_LENGTH = 64

    fun isValid(obfuscatedAccountId: String): Boolean =
        obfuscatedAccountId.isNotBlank() &&
            obfuscatedAccountId.length <= MAX_LENGTH &&
            !EmailValidator.isValid(obfuscatedAccountId)
}
