package io.galva.iam.bridge

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class BridgeResponse(
     val requestId: String,  val result: JsonElement
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class BridgeStringResponse(
     val requestId: String,  val result: String
)