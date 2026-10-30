package io.galva.core.protocol

import io.galva.common.InternalGalvaApi
import io.galva.network.response.SDKInitializeResponse
import kotlinx.coroutines.flow.Flow

@InternalGalvaApi
interface SdkConfigStore {
    suspend fun loadAndSaveConfig(): SDKInitializeResponse?
    suspend fun currentConfig(): SDKInitializeResponse?

    fun currentConfigFlow(): Flow<SDKInitializeResponse?>
}