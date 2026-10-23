package io.galva.core.protocol

import io.galva.common.logger.LogLevel
import io.galva.common.logger.LogSink

data class Configuration @JvmOverloads constructor(
    val apiKey: String,
    val logLevel: LogLevel = LogLevel.WARN,
    /** Emit `session_start` on cold start and on returning after 30+ minutes since the last one. */
    val autoTrackSessions: Boolean = true,
    val env: Environment = Environment.Production,
    /** Show in-app messages automatically on the visible activity; no `getInAppMessage()` collector needed. */
    val autoDisplayInAppMessages: Boolean = false,
    /** Receives SDK logs instead of Logcat (e.g. to forward them to Crashlytics); the level filter still applies. */
    val logSink: LogSink? = null,
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
    }
}
