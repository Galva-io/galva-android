package io.galva.sdk

import android.net.Uri
import io.galva.common.logger.NoOpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The public API promises that every call returns immediately and the work runs on a background
 * queue. On an unconfigured instance nothing consumes that queue, so each call measures exactly the
 * caller-side (main-thread) cost: validation plus an enqueue.
 */
@RunWith(RobolectricTestRunner::class)
class MainThreadBudgetTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val galva = Galva(NoOpLogger, scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun publicCalls_returnWithinPerCallBudget() {
        val galvaLink = Uri.parse("gvdemo://openCommunication?communicationId=perf")
        val otherLink = Uri.parse("https://example.com/path")
        val push = mapOf("communicationId" to "perf", "title" to "t", "body" to "b")
        val calls = linkedMapOf<String, () -> Unit>(
            "identify" to { galva.identify("user-1", "user@example.com", "account-1") },
            "updateProperties" to {
                galva.updateProperties(ProfileProperty.Email("user@example.com"), ProfileProperty.Custom("plan", "pro"))
            },
            "logout" to { galva.logout() },
            "track" to { galva.track("Perf") },
            "track(attributes)" to { galva.track("Perf", mapOf("sku" to "pro", "price" to 9.99, "trial" to true)) },
            "checkForMessages" to { galva.checkForMessages() },
            "reconcileTransactions" to { galva.reconcileTransactions() },
            "currentUserId" to { galva.currentUserId },
            "isAnonymous" to { galva.isAnonymous },
            "setOptOut" to { galva.setOptOut(false) },
            "isOptedOut" to { galva.isOptedOut },
            "isConfigured" to { galva.isConfigured },
            "setPushToken" to { galva.setPushToken("token-1") },
            "clearPushToken" to { galva.clearPushToken() },
            "isGalvaNotification" to { galva.isGalvaNotification(push) },
            "handleOpenURL(galva)" to { check(galva.handleOpenURL(galvaLink)) },
            "handleOpenURL(other)" to { check(!galva.handleOpenURL(otherLink)) },
            "setLogLevel" to { galva.setLogLevel(LogLevel.DEBUG) },
            "setEnableLogging" to { galva.setEnableLogging(true) },
            "setLogger" to { galva.setLogger { _, _, _, _ -> } },
        )

        val results = calls.mapValues { (_, call) -> measure(call) }

        results.forEach { (name, stats) ->
            println(String.format(Locale.US, "%-22s mean %.4f ms  p99 %.4f ms  max %.4f ms", name, stats.meanMs, stats.p99Ms, stats.maxMs))
        }
        val slow = results.filterValues { it.p99Ms >= P99_BUDGET_MS || it.meanMs >= MEAN_BUDGET_MS }
        assertTrue(
            "over budget (mean < $MEAN_BUDGET_MS ms, p99 < $P99_BUDGET_MS ms per call): $slow",
            slow.isEmpty(),
        )
    }

    private data class Stats(val meanMs: Double, val p99Ms: Double, val maxMs: Double)

    private fun measure(call: () -> Unit): Stats {
        repeat(WARM_UP) { call() }
        val samples = DoubleArray(SAMPLES) {
            val start = System.nanoTime()
            call()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return Stats(samples.average(), samples[(SAMPLES * 0.99).toInt() - 1], samples.last())
    }

    private companion object {
        const val WARM_UP = 200
        const val SAMPLES = 1_000

        /** Generous: a fraction of a 16 ms frame, for calls that should take microseconds. */
        const val MEAN_BUDGET_MS = 1.0
        const val P99_BUDGET_MS = 4.0
    }
}
