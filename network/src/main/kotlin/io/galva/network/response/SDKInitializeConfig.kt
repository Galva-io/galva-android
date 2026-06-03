package io.galva.network.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SDKInitializeResponse(
    val data: SDKInitializeConfig,
)

@Serializable
data class SDKInitializeConfig(
    val webviewVersions: List<String>,
    val batchCollection: BatchCollectionConfig,
    @SerialName("playstore") val playStoreConfig: PlayStoreConfig
)

@Serializable
data class PlayStoreConfig(
    val productIds: List<String>,
)

@Serializable
data class BatchCollectionConfig(
    val flushSize: Int, val flushIntervalMs: Int
)