package io.galva.iam.bridge

import io.galva.common.InternalGalvaApi
import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@SuppressLint("UnsafeOptInUsageError")
@Serializable
@InternalGalvaApi
data class BridgeMessage(val name: String, val requestId: String, val payload: JsonObject?)