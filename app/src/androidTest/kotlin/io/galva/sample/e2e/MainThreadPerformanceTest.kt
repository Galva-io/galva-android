package io.galva.sample.e2e

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.galva.common.logger.LogLevel
import io.galva.core.protocol.identity.ProfileProperty
import io.galva.sdk.Galva
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** How long SDK entry points block the main thread in the real app process. */
@RunWith(AndroidJUnit4::class)
class MainThreadPerformanceTest {

    @Test
    fun configure_staysWithinMainThreadBudget() {
        val measured = E2EApplication.configureMainThreadMs
        report(
            "Galva.configure: wall %.2f ms, main-thread cpu %.2f ms (budget %d ms)",
            measured, E2EApplication.configureMainThreadCpuMs, CONFIGURE_BUDGET_MS,
        )
        assertTrue("configure was not measured", measured >= 0)
        assertTrue(
            "Galva.configure blocked the main thread for ${fmt(measured)} ms (budget $CONFIGURE_BUDGET_MS ms)",
            measured < CONFIGURE_BUDGET_MS,
        )
    }

    @Test
    fun publicCalls_onConfiguredSdk_onlyEnqueue() {
        val galva = Galva.instance
        val nonGalvaLink = Uri.parse("https://example.com/not-a-galva-link")
        val calls = linkedMapOf<String, () -> Unit>(
            "updateProperties" to { galva.updateProperties(ProfileProperty.Custom("e2e_perf", 1)) },
            "handleOpenURL(non-Galva)" to { galva.handleOpenURL(nonGalvaLink) },
            "currentUserId" to { galva.currentUserId },
            "isOptedOut" to { galva.isOptedOut },
            "setLogLevel" to { galva.setLogLevel(LogLevel.VERBOSE) },
        )
        val samples = linkedMapOf<String, List<Double>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            calls.forEach { (name, call) ->
                repeat(WARM_UP) { call() }
                samples[name] = List(SAMPLES) { timeMs(call) }.sorted()
            }
        }
        // The median is the call's own cost; single samples also catch preemption and GC pauses.
        val medians = samples.mapValues { (_, sorted) -> sorted[sorted.size / 2] }
        samples.forEach { (name, sorted) ->
            report("%s: median %.3f ms, worst %.3f ms over %d main-thread calls", name, sorted[sorted.size / 2], sorted.last(), SAMPLES)
        }
        val overBudget = medians.filterValues { it >= PER_CALL_BUDGET_MS }
        assertTrue("median per-call cost over $PER_CALL_BUDGET_MS ms: $overBudget", overBudget.isEmpty())
    }

    private fun timeMs(call: () -> Unit): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        call()
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }

    private fun report(format: String, vararg args: Any) {
        Log.i(E2EApplication.TAG, String.format(Locale.US, format, *args))
    }

    private fun fmt(ms: Double) = String.format(Locale.US, "%.2f", ms)

    private companion object {
        /**
         * configure only records the configuration and registers lifecycle observers; everything
         * else is built in the background. Measured ~80–100 ms on a loaded emulator (cold debug
         * process, where first-use class loading dominates), so this leaves room for noise while
         * still catching heavy work creeping back onto the main thread.
         */
        const val CONFIGURE_BUDGET_MS = 500

        /** Generous for calls that only enqueue or read memory (a 60 Hz frame is 16 ms). */
        const val PER_CALL_BUDGET_MS = 2.0
        const val WARM_UP = 20
        const val SAMPLES = 101
    }
}
