package io.galva.network

sealed class NetworkError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    class Timeout(cause: Throwable? = null) : NetworkError("Request timed out", cause)

    class NoConnectivity(cause: Throwable? = null) : NetworkError("No network connectivity", cause)

    class Transport(message: String, cause: Throwable? = null) : NetworkError(message, cause)

}