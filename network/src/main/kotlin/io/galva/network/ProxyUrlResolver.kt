package io.galva.network

import io.galva.common.InternalGalvaApi
import java.net.URI

/**
 * Resolves an apiFetch path against the API base URL. The contract is "relative path only":
 * absolute and scheme-relative references are rejected, and the result must stay on the API origin,
 * so a message bundle can never send the API key to another host. Mirrors the iOS SDK.
 */
@InternalGalvaApi
object ProxyUrlResolver {
    fun resolve(path: String, baseUrl: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || "://" in trimmed || trimmed.startsWith("//")) return null
        val reference = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return runCatching {
            val base = URI(baseUrl)
            val resolved = base.resolve(reference)
            resolved.takeIf {
                it.scheme == base.scheme && it.host == base.host && it.port == base.port
            }?.toString()
        }.getOrNull()
    }
}
