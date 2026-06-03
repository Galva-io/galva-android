package io.galva.common.logger

/**
 * Receives formatted log lines. Production wires this to `android.util.Log`;
 * tests inject a recording impl. All implementations must be thread-safe.
 */
fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}