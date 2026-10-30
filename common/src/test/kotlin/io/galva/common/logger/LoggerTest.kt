package io.galva.common.logger

import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class LoggerTest {
    private lateinit var sink: RecordingSink
    private lateinit var logger: Logger

    @Before fun setUp() {
        sink = RecordingSink()
        logger = Logger.create("Test", sink, level = LogLevel.DEBUG)
    }

    // ── Routing ──────────────────────────────────────────────────

    @Test fun `error routes through the sink`() {
        logger.error("oops")
        val e = sink.entries.single()
        assertEquals(LogLevel.ERROR, e.level)
        assertEquals("Test", e.tag)
        assertEquals("oops", e.message)
        assertNull(e.throwable)
    }

    @Test fun `each level emits an entry`() {
        logger.setLogLevel(LogLevel.VERBOSE)
        logger.error("e"); logger.warn("w"); logger.info("i")
        logger.debug("d"); logger.verbose("v")
        assertEquals(
            listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG, LogLevel.VERBOSE),
            sink.entries.map { it.level },
        )
    }

    @Test fun `throwable is forwarded`() {
        val cause = RuntimeException("boom")
        logger.error("oops", cause)
        assertSame(cause, sink.entries.single().throwable)
    }

    // ── Level filtering ──────────────────────────────────────────

    @Test fun `messages above current level are dropped`() {
        logger.setLogLevel(LogLevel.WARN)
        logger.error("e"); logger.warn("w"); logger.info("i")
        logger.debug("d"); logger.verbose("v")
        assertEquals(listOf(LogLevel.ERROR, LogLevel.WARN), sink.entries.map { it.level })
    }

    @Test fun `level NONE silences every call`() {
        logger.setLogLevel(LogLevel.NONE)
        logger.error("e"); logger.warn("w"); logger.info("i")
        assertTrue(sink.entries.isEmpty())
    }

    @Test fun `level VERBOSE passes everything`() {
        logger.setLogLevel(LogLevel.VERBOSE)
        logger.verbose("v")
        assertEquals(1, sink.entries.size)
    }

    // ── Master switch ────────────────────────────────────────────

    @Test fun `disable silences all output`() {
        logger.setEnableLogging(false)
        logger.error("e"); logger.info("i")
        assertTrue(sink.entries.isEmpty())
    }

    @Test fun `enable resumes output`() {
        logger.setEnableLogging(false)
        logger.error("hidden")
        logger.setEnableLogging(true);  logger.error("visible")
        assertEquals(listOf("visible"), sink.entries.map { it.message })
    }

    @Test fun `disabled logger ignores level changes`() {
        logger.setEnableLogging(false)
        logger.setLogLevel(LogLevel.VERBOSE)
        logger.error("e")
        assertTrue(sink.entries.isEmpty())
    }

    // ── Lazy variant ─────────────────────────────────────────────

    @Test fun `lazy lambda not invoked when filtered`() {
        logger.setLogLevel(LogLevel.WARN)
        var built = 0
        logger.debug { built++; "expensive" }
        assertEquals(0, built)
        assertTrue(sink.entries.isEmpty())
    }

    @Test fun `lazy lambda runs only when emitted`() {
        logger.setLogLevel(LogLevel.DEBUG)
        var built = 0
        logger.debug { built++; "built" }
        assertEquals(1, built)
        assertEquals("built", sink.entries.single().message)
    }

    // ── isLoggable ───────────────────────────────────────────────

    @Test fun `isLoggable mirrors filter behavior`() {
        logger.setLogLevel(LogLevel.INFO)
        assertTrue (logger.isLoggable(LogLevel.ERROR))
        assertTrue (logger.isLoggable(LogLevel.WARN))
        assertTrue (logger.isLoggable(LogLevel.INFO))
        assertFalse(logger.isLoggable(LogLevel.DEBUG))
        assertFalse(logger.isLoggable(LogLevel.VERBOSE))
        assertFalse(logger.isLoggable(LogLevel.NONE))

        logger.setEnableLogging(false)
        assertFalse(logger.isLoggable(LogLevel.ERROR))
    }

    // ── Sink robustness ──────────────────────────────────────────

    @Test fun `sink exception does not propagate`() {
        val throwingLogger = Logger.create(
            tag = "X",
            sink = LogSink { _, _, _, _ -> throw RuntimeException("sink fail") },
            level = LogLevel.DEBUG,
        )
        throwingLogger.error("anything")                   // must not throw
    }

    // ── Thread safety smoke test ─────────────────────────────────

    @Test fun `concurrent writes do not crash`() = runTest{
        logger.setLogLevel(LogLevel.VERBOSE)

        val threads = (1..8).map {
            launch {
                repeat(100) { logger.info("t-$it-msg") }
            }
        }
        threads.joinAll()
        assertEquals(800, sink.entries.size)
    }
}