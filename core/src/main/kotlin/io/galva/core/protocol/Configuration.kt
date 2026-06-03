package io.galva.core.protocol

import io.galva.common.logger.LogLevel

data class Configuration(
    val apiKey: String,
    val logLevel: LogLevel = LogLevel.WARN,
    val autoTrackSessions: Boolean = true,
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
    }
}