package io.galva.network.android

import io.galva.common.logger.Logger
import io.galva.common.logger.NoOpLogger
import io.galva.network.HttpClient

class OkHttpClientBuilder {
    private var apiKey: String = ""
    private var sdkVersion: String = "kotlin/1.0.0"
    private var logger: Logger? = null

    fun sdkVersion(value:String) = apply {
        this.sdkVersion = value
    }

    fun apiKey(value: String) = apply { apiKey = value }
    fun logger(value: Logger) = apply { logger = value }

    fun build(): HttpClient {
        return HttpUrlConnectionClient(apiKey,sdkVersion, logger ?: NoOpLogger)
    }

}