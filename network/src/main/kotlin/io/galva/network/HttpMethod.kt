package io.galva.network

import io.galva.common.InternalGalvaApi
import androidx.annotation.Keep

@Keep
@InternalGalvaApi
enum class HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD
}