package io.galva.network.response

import io.galva.common.InternalGalvaApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@InternalGalvaApi
data class SDKInitializeResponse(
    val data: SDKInitializeConfig,
)

@Serializable
@InternalGalvaApi
data class SDKInitializeConfig(
    val webviewVersions: List<String>,
    val batchCollection: BatchCollectionConfig,
    @SerialName("playstore") val playStoreConfig: PlayStoreConfig
)

@Serializable
@InternalGalvaApi
data class PlayStoreConfig(
    val productIds: List<String>,
)

@Serializable
@InternalGalvaApi
data class BatchCollectionConfig(
    val flushSize: Int, val flushIntervalMs: Int
)