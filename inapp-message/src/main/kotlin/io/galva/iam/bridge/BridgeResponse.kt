package io.galva.iam.bridge

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class BridgeResponse(
    private val requestId: String, private val result: String
)