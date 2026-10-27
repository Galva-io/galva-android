package io.galva.sample.e2e

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.galva.sample.perf.PerfBaselineActivity
import io.galva.sample.perf.PerfProbe
import io.galva.sample.perf.PerfSdkActivity
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Galva's footprint in a real app process, as a delta against a baseline with no Galva at all,
 * like the iOS demo's PerformanceUITests. Each run is a fresh process (see `PerfProbe`); runs
 * alternate baseline / SDK and the median of each is compared:
 * - GATES on extra memory (PSS) at idle;
 * - reports launch-time and CPU deltas (informational, noisy on emulators).
 *
 * Runs only through `scripts/perf.sh`, which also writes the report to `perf-report.md`.
 */
@RunWith(AndroidJUnit4::class)
class SdkFootprintTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Before
    fun onlyInPerformanceRuns() = assumeTrue("run with scripts/perf.sh", E2ETestRunner.performanceRun)

    @Test
    fun sdkOverhead_withinBudget() {
        PerfProbe.dir(app).mkdirs()
        PerfProbe.backendFile(app).writeText("${MockBackend.apiUrl}\n${MockBackend.webviewUrl}\n")

        val baseline = mutableListOf<Metrics>()
        val sdk = mutableListOf<Metrics>()
        repeat(RUNS) { run ->
            measure(PerfBaselineActivity::class.java, ":perf_baseline", "baseline-$run")?.let(baseline::add)
            measure(PerfSdkActivity::class.java, ":perf_sdk", "sdk-$run")?.let(sdk::add)
        }
        assertTrue(
            "perf probe never reported (baseline=${baseline.size}, sdk=${sdk.size})",
            baseline.isNotEmpty() && sdk.isNotEmpty(),
        )

        val memory = Row("Memory, PSS (MB)", baseline.median { it.memoryMb }, sdk.median { it.memoryMb },
            budget = "< ${fmt(MEMORY_BUDGET_MB)}", gating = true) { it < MEMORY_BUDGET_MB }
        val rows = listOf(
            memory,
            Row("Launch to first frame (ms)", baseline.median { it.launchMs }, sdk.median { it.launchMs },
                budget = "ref ${fmt(LAUNCH_REFERENCE_MS)}", gating = false),
            Row("CPU (ms)", baseline.median { it.cpuMs }, sdk.median { it.cpuMs },
                budget = "report-only", gating = false),
        )
        emitReport(rows, runs = minOf(baseline.size, sdk.size))

        assertTrue(
            "Galva adds ${fmt(memory.delta)} MB of memory at idle (baseline ${fmt(memory.baseline)} → " +
                "SDK ${fmt(memory.sdk)}): over the ${fmt(MEMORY_BUDGET_MB)} MB budget",
            memory.pass,
        )
    }

    // ── Sampling ────────────────────────────────────────────────────────────

    private data class Metrics(val memoryMb: Double, val launchMs: Double, val cpuMs: Double)

    /** Starts one fresh probe process and returns what it measured, or null if it never reported. */
    private fun measure(activity: Class<out Activity>, processSuffix: String, runId: String): Metrics? {
        val process = app.packageName + processSuffix
        awaitGone(process)
        val result = PerfProbe.resultFile(app, runId).apply { delete() }
        app.startActivity(
            Intent(app, activity).putExtra(PerfProbe.EXTRA_RUN_ID, runId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val json = poll(PROBE_TIMEOUT_MS) { result.takeIf { it.exists() }?.readText() }
        if (json == null) {
            Log.w(TAG, "$runId: probe did not report within $PROBE_TIMEOUT_MS ms")
            return null
        }
        awaitGone(process)
        val parsed = JSONObject(json)
        return parsed.run { Metrics(getDouble("memoryMb"), getDouble("launchMs"), getDouble("cpuMs")) }
            .also { Log.i(TAG, "$runId: $it; busiest threads (ms): ${parsed.optJSONObject("threads")}") }
    }

    /** The probe kills its process after reporting; the next run must not reuse it. */
    private fun awaitGone(processName: String) {
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        poll(PROCESS_EXIT_TIMEOUT_MS) {
            Unit.takeIf { activityManager.runningAppProcesses.orEmpty().none { it.processName == processName } }
        }
    }

    private fun <T : Any> poll(timeoutMs: Long, probe: () -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return probe()
    }

    private fun List<Metrics>.median(value: (Metrics) -> Double): Double {
        val sorted = map(value).filterNot(Double::isNaN).sorted()
        return if (sorted.isEmpty()) Double.NaN else sorted[sorted.size / 2]
    }

    // ── Report ──────────────────────────────────────────────────────────────

    private class Row(
        val name: String,
        val baseline: Double,
        val sdk: Double,
        val budget: String,
        val gating: Boolean,
        check: (delta: Double) -> Boolean = { true },
    ) {
        val delta = sdk - baseline
        val pass = check(delta)
    }

    private fun emitReport(rows: List<Row>, runs: Int) {
        val report = buildString {
            appendLine("# Galva Android SDK performance report")
            appendLine()
            appendLine("Median of $runs runs each: the demo app **with** Galva vs **without** it (baseline), debug build.")
            appendLine()
            appendLine("| Metric | Baseline | With SDK | Delta | Budget | Status |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { row ->
                val status = if (!row.gating) "—" else if (row.pass) "✅ pass" else "❌ FAIL"
                val sign = if (row.delta >= 0) "+" else ""
                appendLine("| ${row.name} | ${fmt(row.baseline)} | ${fmt(row.sdk)} | $sign${fmt(row.delta)} | ${row.budget} | $status |")
            }
        }
        // Extracted by scripts/perf.sh into perf-report.md.
        Log.i(TAG, REPORT_BEGIN)
        report.lines().forEach { Log.i(TAG, it.ifEmpty { " " }) }
        Log.i(TAG, REPORT_END)
    }

    private fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)

    private companion object {
        const val TAG = "GalvaPerf"
        const val REPORT_BEGIN = "===GALVA-PERF-REPORT-BEGIN==="
        const val REPORT_END = "===GALVA-PERF-REPORT-END==="

        const val RUNS = 5

        /** Gating budget: extra memory Galva may add at idle (same as the iOS suite). */
        const val MEMORY_BUDGET_MB = 8.0

        /** Report-only reference for launch overhead (same as the iOS suite). */
        const val LAUNCH_REFERENCE_MS = 150.0

        const val PROBE_TIMEOUT_MS = 60_000L
        const val PROCESS_EXIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
