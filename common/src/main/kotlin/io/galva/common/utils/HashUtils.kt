package io.galva.common.utils

import java.security.MessageDigest

object HashUtils {
    // Derive a stable 64-char token from any upstream identifier.
// SHA-256 hex is 64 chars — fits the Play limit exactly and contains no PII.
    fun hashToSafeToken(upstreamId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(upstreamId.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}