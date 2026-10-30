package io.galva.sample.e2e

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.galva.sdk.LogLevel
import io.galva.sdk.ProfileProperty
import io.galva.sample.MainActivity
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
        // The measured updateProperties calls leave ~120 updates on the SDK queue, which a loaded
        // emulator persists at up to ~1 s each; let them reach the mock so the next test's events
        // are not stuck behind them.
        ActivityScenario.launch(MainActivity::class.java).use { drainSdkQueue(DRAIN_TIMEOUT_MS) }
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
         * else is built in the background. Typical on the loaded dev emulator: 80–190 ms (cold debug
         * process, first-use class loading). The old synchronous configure took 1.1–3.6 s there, so
         * this still catches heavy work moving back to the main thread. A swapping emulator can
         * spike a single cold start to seconds (zram decompression is billed to the faulting
         * thread); re-run before treating one failure as a regression.
         */
        const val CONFIGURE_BUDGET_MS = 1_000

        /** Generous for calls that only enqueue or read memory (a 60 Hz frame is 16 ms). */
        const val PER_CALL_BUDGET_MS = 2.0
        const val WARM_UP = 20
        const val SAMPLES = 101
        const val DRAIN_TIMEOUT_MS = 180_000L
    }
}
