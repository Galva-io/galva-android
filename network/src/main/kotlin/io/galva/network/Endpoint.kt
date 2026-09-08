package io.galva.network

/**
 * Names the SDK's known API endpoints. Concrete URLs are resolved at call time
 * using the base URL from [Configuration] — so the same endpoint switches between
 * production, staging, or EU regions without code changes.
 */
enum class Endpoint(val path: String) {
    IDENTIFY("/identities"),
    SDK("/sdk"),
    Products("/products"),
    Communications("/communications"),
    EMPTY("")
}

fun Endpoint.toUrl(baseUrl: String): String {
    val base = baseUrl.removeSuffix("/")
    return "$base$path"
}