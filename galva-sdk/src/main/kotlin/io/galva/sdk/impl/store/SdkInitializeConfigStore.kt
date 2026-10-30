package io.galva.sdk.impl.store

import io.galva.common.logger.Logger
import io.galva.core.protocol.SdkConfigStore
import io.galva.localstorage.core.KeyValueStorage
import io.galva.localstorage.core.getObject
import io.galva.localstorage.core.observeObject
import io.galva.localstorage.core.putObject
import io.galva.network.request.sdk.InitConfigSdkRequest
import io.galva.network.response.SDKInitializeResponse
import io.galva.network.service.SdkService
import io.galva.network.service.ServiceResult
import io.galva.sdk.iam.BuildConfig
import kotlinx.coroutines.flow.Flow

internal class SdkInitializeConfigStore(
    private val sdkService: SdkService,
    private val keyValueStorage: KeyValueStorage,
    private val logger: Logger,
) : SdkConfigStore {
    override suspend fun loadAndSaveConfig(): SDKInitializeResponse? {
        val config =
            sdkService.getInitializeConfig(InitConfigSdkRequest(BuildConfig.JS_BRIDGE_VERSION))
        return when (config) {
            is ServiceResult.Success<SDKInitializeResponse> -> {
                keyValueStorage.putObject(
                    "sdk_config", config.value
                )
                config.value
            }

            is ServiceResult.Failure -> {
                if (config.status == 401 || config.status == 403) {
                    logger.error {
                        "sdk/initialize rejected with HTTP ${config.status}: check the apiKey in Configuration " +
                            "and that it belongs to this app's environment"
                    }
                } else {
                    logger.warn { "sdk/initialize failed with HTTP ${config.status}; using the cached configuration" }
                }
                currentConfig()
            }

            is ServiceResult.Error -> {
                logger.info { "sdk/initialize not reachable (offline?); using the cached configuration" }
                currentConfig()
            }

            // Logged by the service.
            is ServiceResult.UnknownError -> currentConfig()
        }
    }

    override suspend fun currentConfig(): SDKInitializeResponse? {
        return keyValueStorage.getObject("sdk_config")
    }

    override fun currentConfigFlow(): Flow<SDKInitializeResponse?> {
        return keyValueStorage.observeObject("sdk_config")
    }
}
