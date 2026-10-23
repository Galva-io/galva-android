package io.galva.iam.bridge

import io.galva.common.InternalGalvaApi
import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@InternalGalvaApi
data class BridgeResponse(
     val requestId: String,  val result: JsonElement
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@InternalGalvaApi
data class BridgeStringResponse(
     val requestId: String,  val result: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@InternalGalvaApi
data class BridgeErrorResponse(
    val requestId: String, val error: BridgeError
)
