package io.galva.sdk

import android.util.Log
import io.galva.common.logger.LogLevel
import io.galva.common.logger.LogSink

class AndroidLogSink: LogSink {

    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val safeTag = if (tag.length > MAX_TAG_LENGTH) tag.take(MAX_TAG_LENGTH) else tag
        when (level) {
            LogLevel.ERROR   -> if (throwable != null) Log.e(safeTag, message, throwable) else Log.e(safeTag, message)
            LogLevel.WARN    -> if (throwable != null) Log.w(safeTag, message, throwable) else Log.w(safeTag, message)
            LogLevel.INFO    -> if (throwable != null) Log.i(safeTag, message, throwable) else Log.i(safeTag, message)
            LogLevel.DEBUG   -> if (throwable != null) Log.d(safeTag, message, throwable) else Log.d(safeTag, message)
            LogLevel.VERBOSE -> if (throwable != null) Log.v(safeTag, message, throwable) else Log.v(safeTag, message)
            LogLevel.NONE    -> Unit
        }
    }

    private companion object { const val MAX_TAG_LENGTH = 23 }
}