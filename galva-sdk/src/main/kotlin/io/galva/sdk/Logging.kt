package io.galva.sdk

import io.galva.common.logger.LogLevel as InternalLogLevel
import io.galva.common.logger.LogSink as InternalLogSink

/** How much the SDK logs. Each level includes the ones before it; [NONE] turns logging off. */
public enum class LogLevel { NONE, ERROR, WARN, INFO, DEBUG, VERBOSE }

/**
 * Receives the SDK's log lines instead of Logcat, e.g. to forward them to Crashlytics. Set it with
 * [Configuration.logSink] or [Galva.setLogger]; the log level still filters what reaches it.
 * Called from SDK threads, so implementations must be thread-safe.
 */
public fun interface LogSink {
    public fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

internal fun LogLevel.toInternal(): InternalLogLevel = InternalLogLevel.valueOf(name)

internal fun InternalLogLevel.toPublic(): LogLevel = LogLevel.valueOf(name)

internal fun LogSink.toInternal(): InternalLogSink =
    InternalLogSink { level, tag, message, throwable -> write(level.toPublic(), tag, message, throwable) }
