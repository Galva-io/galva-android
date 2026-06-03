package io.galva.common.logger

class Logger internal constructor(
    private val tag: String,
    private val sink: LogSink,
    initialLevel: LogLevel,
    initialEnabled: Boolean,
) {

    @Volatile private var level: LogLevel = initialLevel
    @Volatile private var enabled: Boolean = initialEnabled

    fun setEnableLogging(enableLogging: Boolean)  { enabled = enableLogging }

    fun setLogLevel(level: LogLevel){
        this.level = level
    }

    fun isLoggable(level: LogLevel): Boolean =
        enabled && level != LogLevel.NONE && level.ordinal <= this.level.ordinal

    fun error(message: String, throwable: Throwable? = null)   = log(LogLevel.ERROR, message, throwable)
    fun warn (message: String, throwable: Throwable? = null)   = log(LogLevel.WARN,  message, throwable)
    fun info (message: String, throwable: Throwable? = null)   = log(LogLevel.INFO,  message, throwable)
    fun debug(message: String, throwable: Throwable? = null)   = log(LogLevel.DEBUG, message, throwable)
    fun verbose(message: String, throwable: Throwable? = null) = log(LogLevel.VERBOSE, message, throwable)

    /** Lazy variants — message lambda only runs when the message is actually emitted. */
    inline fun error(throwable: Throwable? = null, message: () -> String)   { if (isLoggable(LogLevel.ERROR))   error(message(), throwable) }
    inline fun warn (throwable: Throwable? = null, message: () -> String)   { if (isLoggable(LogLevel.WARN))    warn (message(), throwable) }
    inline fun info (throwable: Throwable? = null, message: () -> String)   { if (isLoggable(LogLevel.INFO))    info (message(), throwable) }
    inline fun debug(throwable: Throwable? = null, message: () -> String)   { if (isLoggable(LogLevel.DEBUG))   debug(message(), throwable) }
    inline fun verbose(throwable: Throwable? = null, message: () -> String) { if (isLoggable(LogLevel.VERBOSE)) verbose(message(), throwable) }

    private fun log(level: LogLevel, message: String, throwable: Throwable?) {
        if (!isLoggable(level)) return
        try {
            sink.write(level, tag, message, throwable)
        } catch (_: Throwable) {
            // never crash callers — sink failures are dropped silently
        }
    }

    companion object {
        @JvmStatic @JvmOverloads
        fun create(
            tag: String,
            sink: LogSink,
            level: LogLevel = LogLevel.VERBOSE,
            enabled: Boolean = true,
        ): Logger = Logger(tag, sink, level, enabled)
    }
}