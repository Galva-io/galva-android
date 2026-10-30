package io.galva.network.service

import io.galva.common.InternalGalvaApi
import io.galva.network.request.sdk.InitConfigSdkRequest
import io.galva.network.response.SDKInitializeResponse

@InternalGalvaApi
interface SdkService {

    suspend fun getInitializeConfig(request: InitConfigSdkRequest): ServiceResult<SDKInitializeResponse>
}