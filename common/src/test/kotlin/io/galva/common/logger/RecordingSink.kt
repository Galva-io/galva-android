package io.galva.common.logger

class RecordingSink : LogSink {
    data class Entry(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    val entries = mutableListOf<Entry>()

    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}