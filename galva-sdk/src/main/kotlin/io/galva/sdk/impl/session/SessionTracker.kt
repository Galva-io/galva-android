package io.galva.sdk.impl.session

import io.galva.common.logger.Logger
import kotlinx.serialization.json.JsonObject

/**
 * Emits `session_start` like the iOS SDK: on a foreground when no session was started yet, or
 * when 30+ minutes passed since the last `session_start`. The timestamp is persisted, so the
 * window also spans app restarts. There is no `session_end`; the backend derives duration.
 *
 * While opted out nothing is emitted and the timestamp is not bumped, so the first foreground
 * after opting back in starts a session.
 */
internal class SessionTracker(
    private val lastSessionStart: LastSessionStartStore,
    private val logger: Logger,
    private val isOptedOut: () -> Boolean,
    private val sessionProperties: () -> JsonObject,
    private val track: suspend (event: String, properties: JsonObject) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface LastSessionStartStore {
        var lastSessionStartAt: Long?
    }

    suspend fun handleForeground() {
        if (isOptedOut()) {
            logger.debug { "session foreground skipped (opted out)" }
            return
        }
        val now = clock()
        val last = lastSessionStart.lastSessionStartAt
        if (last != null && now - last < SESSION_WINDOW_MS) {
            logger.debug { "session continues — ${(now - last) / 1000}s since the last session_start" }
            return
        }
        lastSessionStart.lastSessionStartAt = now
        logger.info { "session_start (first=${last == null})" }
        track(EVENT, sessionProperties())
    }

    companion object {
        const val EVENT = "session_start"
        const val SESSION_WINDOW_MS = 30 * 60 * 1000L
    }
}
