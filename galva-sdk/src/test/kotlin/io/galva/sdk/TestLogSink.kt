package io.galva.sdk

import io.galva.common.logger.LogLevel
import io.galva.common.logger.LogSink
import java.util.concurrent.CopyOnWriteArrayList

class TestLogSink : LogSink {
    data class Record(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    private val _records = CopyOnWriteArrayList<Record>()

    /** All records, in order. */
    val records: List<Record> get() = _records.toList()

    /** All messages, in order. */
    val messages: List<String> get() = _records.map { it.message }

    /** All throwables captured. */
    val throwables: List<Throwable> get() = _records.mapNotNull { it.throwable }

    override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        _records.add(Record(level, tag, message, throwable))
    }

    /** Reset captured records. */
    fun clear() {
        _records.clear()
    }

    /** True if any captured message contains the given substring. */
    fun hasMessageContaining(substring: String): Boolean =
        _records.any { it.message.contains(substring) }

    /** True if any record at the given level contains the substring. */
    fun hasMessageAtLevel(level: LogLevel, substring: String): Boolean =
        _records.any { it.level == level && it.message.contains(substring) }

    /** Messages emitted at a specific level. */
    fun messagesAtLevel(level: LogLevel): List<String> =
        _records.filter { it.level == level }.map { it.message }

    /** Records at a specific level. */
    fun recordsAtLevel(level: LogLevel): List<Record> =
        _records.filter { it.level == level }

    /** Records with a specific tag. */
    fun recordsWithTag(tag: String): List<Record> =
        _records.filter { it.tag == tag }
}