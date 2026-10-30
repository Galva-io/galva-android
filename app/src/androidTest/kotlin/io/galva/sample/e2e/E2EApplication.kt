package io.galva.sample.e2e

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import io.galva.sdk.LogLevel
import io.galva.sdk.Configuration
import io.galva.sdk.Environment
import io.galva.sdk.Galva
import java.util.Locale

/** Same setup as the demo's MainApplication, pointed at [MockBackend]; also times `configure`. */
class E2EApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (E2ETestRunner.performanceRun) return
        val configuration = Configuration(
            apiKey = MockBackend.API_KEY,
            logLevel = LogLevel.VERBOSE,
            env = Environment.Custom(apiUrl = MockBackend.apiUrl, webviewUrl = MockBackend.webviewUrl),
            autoDisplayInAppMessages = true,
        )
        val wallStart = SystemClock.elapsedRealtimeNanos()
        val cpuStart = Debug.threadCpuTimeNanos()
        Galva.configure(this, configuration)
        configureMainThreadCpuMs = (Debug.threadCpuTimeNanos() - cpuStart) / 1_000_000.0
        configureMainThreadMs = (SystemClock.elapsedRealtimeNanos() - wallStart) / 1_000_000.0
        Log.i(
            TAG,
            String.format(
                Locale.US, "Galva.configure main thread: wall %.2f ms, cpu %.2f ms",
                configureMainThreadMs, configureMainThreadCpuMs,
            ),
        )

        // Load WebView's native code now, while no window can receive input. On a slow emulator its
        // first load (when the first message opens) can outlast the 5 s input-dispatch ANR limit,
        // which kills the instrumentation run. Done after the measurement so it is not counted.
        runCatching { WebSettings.getDefaultUserAgent(this) }
    }

    companion object {
        const val TAG = "GalvaE2E"

        /** Wall time `Galva.configure` blocked the main thread in Application.onCreate. */
        @Volatile
        var configureMainThreadMs: Double = -1.0
            private set

        /** CPU time the main thread spent inside `Galva.configure` (excludes waiting for a CPU / disk). */
        @Volatile
        var configureMainThreadCpuMs: Double = -1.0
            private set
    }
}
