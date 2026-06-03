package io.galva.core.protocol

import io.galva.network.response.SDKInitializeResponse
import kotlinx.coroutines.flow.Flow

interface SdkConfigStore {
    suspend fun loadAndSaveConfig(): SDKInitializeResponse?
    suspend fun currentConfig(): SDKInitializeResponse?

    fun currentConfigFlow(): Flow<SDKInitializeResponse?>
}