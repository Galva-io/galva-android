package io.galva.sdk.impl.session

import io.galva.common.logger.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTrackerTest {
    private val store = object : SessionTracker.LastSessionStartStore {
        override var lastSessionStartAt: Long? = null
    }
    private var now = 1_000_000L
    private var optedOut = false
    private val events = mutableListOf<String>()

    private val tracker = SessionTracker(
        lastSessionStart = store,
        logger = NoOpLogger,
        isOptedOut = { optedOut },
        sessionProperties = { JsonObject(mapOf("sdk_version" to JsonPrimitive("1.0"))) },
        track = { event, _ -> events += event },
        clock = { now },
    )

    @Test
    fun `first foreground starts a session`() = runTest {
        tracker.handleForeground()

        assertEquals(listOf("session_start"), events)
        assertEquals(now, store.lastSessionStartAt)
    }

    @Test
    fun `foregrounds within 30 minutes continue the session`() = runTest {
        tracker.handleForeground()
        now += SessionTracker.SESSION_WINDOW_MS - 1

        tracker.handleForeground()

        assertEquals(1, events.size)
    }

    @Test
    fun `a foreground 30 minutes after the last session_start starts a new one`() = runTest {
        tracker.handleForeground()
        now += SessionTracker.SESSION_WINDOW_MS

        tracker.handleForeground()

        assertEquals(2, events.size)
    }

    @Test
    fun `the window is measured from the persisted timestamp, across restarts`() = runTest {
        store.lastSessionStartAt = now - 60_000

        tracker.handleForeground()

        assertEquals(0, events.size)
    }

    @Test
    fun `opted out emits nothing and does not bump the timestamp`() = runTest {
        optedOut = true

        tracker.handleForeground()

        assertEquals(0, events.size)
        assertNull(store.lastSessionStartAt)
    }
}
