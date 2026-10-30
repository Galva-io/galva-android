package io.galva.network

import io.galva.common.InternalGalvaApi

/**
 * Names the SDK's known API endpoints. Concrete URLs are resolved at call time
 * using the base URL of the configured environment — so the same endpoint switches between
 * production, staging, or EU regions without code changes.
 */
@InternalGalvaApi
enum class Endpoint(val path: String) {
    IDENTIFY("/identities"),
    SDK("/sdk"),
    Products("/products"),
    Communications("/communications"),
    Transactions("/v1/transactions"),
    EMPTY("")
}

@InternalGalvaApi
fun Endpoint.toUrl(baseUrl: String): String {
    val base = baseUrl.removeSuffix("/")
    return "$base$path"
}