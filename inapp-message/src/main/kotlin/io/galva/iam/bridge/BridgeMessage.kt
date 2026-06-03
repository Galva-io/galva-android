package io.galva.iam.bridge

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class BridgeMessage(val name: String, val requestId: String, val payload: JsonObject?)