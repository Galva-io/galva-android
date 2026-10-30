package io.galva.sample.perf

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import io.galva.sdk.LogLevel
import io.galva.sdk.Configuration
import io.galva.sdk.Environment
import io.galva.sample.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * The performance probe (debug and profiling builds), like the iOS demo's MetricsProbe. Each measurement
 * is a fresh process running this app with Galva (`:perf_sdk`) or without it (`:perf_baseline`),
 * so the performance suite can report Galva's cost as a delta. A run measures:
 * - `launchMs`: process start → first frame;
 * - `memoryMb`: total PSS of the process after a settle;
 * - `cpuMs`: CPU time the process used by then.
 *
 * Profiling builds also record a sampling method trace of the whole process from
 * `Application.onCreate` to the sample, for scripts/profile.sh.
 */
object PerfProbe {
    const val MODE_BASELINE = "baseline"
    const val MODE_SDK = "sdk"
    const val EXTRA_RUN_ID = "run_id"

    /** How long after the first frame the sample is taken, so launch work has settled. */
    const val SETTLE_MS = 2_000L

    fun dir(app: Application) = File(app.filesDir, "galva_perf")

    /** The mock backend the SDK probe talks to, written by the performance test. */
    fun backendFile(app: Application) = File(dir(app), "backend.txt")

    fun resultFile(app: Application, runId: String) = File(dir(app), "$runId.json")

    /**
     * Galva's configuration for this process: [default] in the app's own processes, the test's
     * mock backend in the SDK probe, and null (no Galva at all) in the baseline probe.
     */
    fun galvaConfiguration(app: Application, default: () -> Configuration): Configuration? {
        val mode = mode(app) ?: return default()
        if (BuildConfig.BUILD_TYPE == PROFILING_BUILD) startTrace(app, mode)
        if (mode == MODE_BASELINE) return null
        val backend = backendFile(app).takeIf(File::exists)?.readLines()
            // No mock backend (profiling builds): the demo's own setup.
            ?: return default().copy(logLevel = LogLevel.WARN)
        val (apiUrl, webviewUrl) = backend
        // Same setup as the demo: auto display on, WARN logging (the shipping default).
        return Configuration(
            apiKey = "perf",
            env = Environment.Custom(apiUrl = apiUrl, webviewUrl = webviewUrl),
            autoDisplayInAppMessages = true,
        )
    }

    private const val PROFILING_BUILD = "profiling"
    private const val TRACE_BUFFER_BYTES = 64 * 1024 * 1024
    private const val TRACE_INTERVAL_US = 500

    @Volatile
    private var tracing = false

    /** Samples every thread's stack until [stopTrace]; the file lands in the app's external files. */
    private fun startTrace(app: Application, mode: String) {
        val dir = app.getExternalFilesDir("galva_perf") ?: return
        val file = File(dir, "$mode-${System.currentTimeMillis()}.trace")
        Debug.startMethodTracingSampling(file.path, TRACE_BUFFER_BYTES, TRACE_INTERVAL_US)
        tracing = true
    }

    fun stopTrace() {
        if (tracing) Debug.stopMethodTracing()
        tracing = false
    }

    private fun mode(app: Application): String? {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim(Char::isISOControl) }.getOrNull()
        }
        return name?.substringAfter(":perf_", missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
    }
}

/** Shown in a fresh probe process: samples it after the first frame, writes the result, exits. */
abstract class PerfProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var sampled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Galva performance probe" })
    }

    override fun onResume() {
        super.onResume()
        if (sampled) return
        sampled = true
        // Runs after the first traversal, i.e. once the first frame is drawn.
        window.decorView.post {
            val launchMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            handler.postDelayed({ sample(launchMs) }, PerfProbe.SETTLE_MS)
        }
    }

    private fun sample(launchMs: Long) {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val result = JSONObject()
            .put("launchMs", launchMs)
            .put("memoryMb", memory.totalPss / 1024.0)
            .put("cpuMs", Process.getElapsedCpuTime())
            .put("threads", busiestThreads())
        val runId = intent.getStringExtra(PerfProbe.EXTRA_RUN_ID) ?: "unknown"
        val target = PerfProbe.resultFile(application, runId)
        target.parentFile?.mkdirs()
        File(target.path + ".tmp").apply { writeText(result.toString()) }.renameTo(target)
        PerfProbe.stopTrace()
        finish()
        // The next run must start a new process.
        Process.killProcess(Process.myPid())
    }

    /** CPU time per thread name (utime + stime from /proc), busiest first: where the CPU went. */
    private fun busiestThreads(): JSONObject {
        val ticksPerMs = 100 / 1000.0 // USER_HZ is 100 on Android
        val byName = sortedMapOf<String, Double>()
        File("/proc/self/task").listFiles().orEmpty().forEach { task ->
            val stat = runCatching { File(task, "stat").readText() }.getOrNull() ?: return@forEach
            val name = stat.substringAfter('(').substringBeforeLast(')').replace(Regex("[-:]?\\d+$"), "")
            val fields = stat.substringAfterLast(')').trim().split(' ')
            val ticks = (fields.getOrNull(11)?.toLongOrNull() ?: 0) + (fields.getOrNull(12)?.toLongOrNull() ?: 0)
            byName[name] = (byName[name] ?: 0.0) + ticks / ticksPerMs
        }
        return JSONObject().apply {
            byName.entries.sortedByDescending { it.value }.take(10).forEach { put(it.key, it.value.toLong()) }
        }
    }
}

/** Runs in `:perf_baseline`: the app without Galva. */
class PerfBaselineActivity : PerfProbeActivity()

/** Runs in `:perf_sdk`: the app with Galva configured against the test's mock backend. */
class PerfSdkActivity : PerfProbeActivity()
