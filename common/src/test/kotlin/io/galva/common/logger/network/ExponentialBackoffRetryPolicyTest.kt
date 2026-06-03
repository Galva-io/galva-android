package io.galva.common.logger.network

import io.galva.common.network.ExponentialBackoffRetryPolicy
import io.galva.common.network.RetryAfterException
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


/** Captures `sleep(duration)` calls instead of actually delaying — fully deterministic. */
private class SleepRecorder {
    val durations = mutableListOf<Duration>()
    val sleep: suspend (Duration) -> Unit = { durations += it }
    fun only(): Duration {
        assertEquals(1, durations.size)
        return durations.single()
    }
}


class ExponentialBackoffRetryPolicyTest {
    @Test
    fun `attempt 0 - window equals base`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 1.seconds, cap = 10.minutes,
            random = MaxRandom(),                                  // always returns window-1
            sleep = rec.sleep,
        )
        policy.backoff(0, RuntimeException())
        // window = 1000ms; full-jitter range [0, 1000); max sample = 999ms
        assertEquals(999.milliseconds, rec.only())
    }
    @Test fun `attempt 3 - window equals base shifted by 3 (8x)`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 100.milliseconds, cap = 1.minutes,
            random = MaxRandom(), sleep = rec.sleep,
        )
        policy.backoff(3, RuntimeException())
        // window = 100 << 3 = 800ms; max sample = 799ms
        assertEquals(799.milliseconds, rec.only())
    }

    @Test fun `attempt 10 - window equals base shifted by 10 (1024x)`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 1.milliseconds, cap = 1.minutes,
            random = MaxRandom(), sleep = rec.sleep,
        )
        policy.backoff(10, RuntimeException())
        // window = 1 << 10 = 1024ms; max sample = 1023ms
        assertEquals(1023.milliseconds, rec.only())
    }
    @Test fun `window is clamped to cap when exponential exceeds it`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 1.seconds, cap = 5.seconds,
            random = MaxRandom(), sleep = rec.sleep,
        )
        // attempt = 4 → exp window = 16s; capped to 5s
        policy.backoff(4, RuntimeException())
        assertEquals(4_999.milliseconds, rec.only())
    }

    @Test fun `extremely high attempt does not overflow — coerced at 30`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 1.seconds, cap = 5.minutes,
            random = MaxRandom(), sleep = rec.sleep,
        )
        policy.backoff(1000, RuntimeException())
        // Without coerceAtMost(30), `1L shl 1000` would be UB / overflow.
        // With cap = 5min = 300_000ms, sample = 299_999ms.
        assertEquals(299_999.milliseconds, rec.only())
    }

    @Test fun `random determines the actual sleep within window`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 1.seconds, cap = 5.minutes,
            random = FixedRandom(0L),                              // bottom of range
            sleep = rec.sleep,
        )
        policy.backoff(2, RuntimeException())
        assertEquals(0.milliseconds, rec.only())
    }

    @Test fun `same seed produces deterministic output`() = runTest {
        val r1 = SleepRecorder(); val r2 = SleepRecorder()
        ExponentialBackoffRetryPolicy(
            base = 100.milliseconds, cap = 1.minutes,
            random = Random(seed = 42L), sleep = r1.sleep,
        ).backoff(5, RuntimeException())

        ExponentialBackoffRetryPolicy(
            base = 100.milliseconds, cap = 1.minutes,
            random = Random(seed = 42L), sleep = r2.sleep,
        ).backoff(5, RuntimeException())

        assertEquals(r1.only(), r2.only())
    }

    @Test fun `samples stay within greater or equals 0 and less than window over many attempts`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 100.milliseconds, cap = 5.minutes,
            random = Random(seed = 123L), sleep = rec.sleep,
        )
        repeat(1_000) {
            policy.backoff(5, RuntimeException())
        }
        // window = 100 << 5 = 3200ms
        rec.durations.forEach {
            assert(it >= 0.milliseconds, {
                "negative sleep: $it"
            })
            assert(it < 3200.milliseconds, {
                "exceeds window: $it"
            })
        }
        // Distribution sanity — average should be roughly window/2 ± noise
        val avgMs = rec.durations.sumOf { it.inWholeMilliseconds } / rec.durations.size
        assert(avgMs in 1400..1800, {
            "average $avgMs not near 1600 (window/2)"
        })
    }
    // ─── Batch handling ────────────────────────────────────────────────

    @Test fun `uses max retryCount from the batch`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 100.milliseconds, cap = 1.minutes,
            random = MaxRandom(), sleep = rec.sleep,
        )
        policy.backoff(5, RuntimeException())

        // window = 100 << 5 = 3200ms; max sample = 3199ms
        assertEquals(3199.milliseconds, rec.only())
    }

    @Test fun `single-op batch uses its retryCount`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 100.milliseconds, cap = 1.minutes,
            random = MaxRandom(), sleep = rec.sleep,
        )
        policy.backoff(2, RuntimeException())
        // window = 100 << 2 = 400ms
        assertEquals(399.milliseconds, rec.only())
    }

    // ─── Sleep invocation ──────────────────────────────────────────────

    @Test fun `sleep is invoked exactly once per backoff call`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 100.milliseconds, cap = 1.minutes,
            random = MaxRandom(), sleep = rec.sleep,
        )
        policy.backoff(0, RuntimeException())
        policy.backoff(1, RuntimeException())
        policy.backoff(2, RuntimeException())
        assertEquals(3, rec.durations.size)
    }

    @Test fun `error parameter is not inspected by default policy`() = runTest {
        val rec = SleepRecorder()
        val policy = ExponentialBackoffRetryPolicy(
            base = 100.milliseconds, cap = 1.minutes,
            random = MaxRandom(), sleep = rec.sleep,
        )
        // Different errors must yield the same sleep window
        policy.backoff(2, RuntimeException())
        policy.backoff(2, IllegalStateException())
        policy.backoff(2, AssertionError())
        assertEquals(1, rec.durations.toSet().size)
    }

    // ─── Real-delay integration test ───────────────────────────────────

    @Test fun `with default sleep, actually delays in virtual time`() = runTest {
        val policy = ExponentialBackoffRetryPolicy(
            base = 1.seconds, cap = 1.minutes,
            random = MaxRandom(),               // forces close to upper bound
            // sleep = default → real `delay(it)`, which is virtual under runTest
        )
        val before = testScheduler.currentTime
        policy.backoff(2, RuntimeException())
        val elapsed = testScheduler.currentTime - before
        // window = 4s, MaxRandom returns 3999 → expect ~3999ms
        assertEquals(3_999, elapsed)
    }

    @Test fun `retry-after delay is honored from RetryAfterException`() = runTest {
        val policy = ExponentialBackoffRetryPolicy(
            base = 1.seconds, cap = 1.minutes,
            random = MaxRandom(),
        )
        val before = testScheduler.currentTime
        policy.backoff(2, RetryAfterException(retryAfter = 2.seconds, null))
        val elapsed = testScheduler.currentTime - before
        assertEquals(2000, elapsed)
    }

}

/** Always returns the maximum allowed value for the requested range. */
private class MaxRandom : Random() {
    override fun nextBits(bitCount: Int): Int = error("unused")
    override fun nextLong(from: Long, until: Long): Long = until - 1
    override fun nextLong(): Long = Long.MAX_VALUE
}

/** Always returns the same fixed value (clamped to range). */
private class FixedRandom(private val value: Long) : Random() {
    override fun nextBits(bitCount: Int): Int = error("unused")
    override fun nextLong(from: Long, until: Long): Long =
        value.coerceIn(from, until - 1)
    override fun nextLong(): Long = value
}