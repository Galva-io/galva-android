package io.galva.sample

import android.app.Application
import io.galva.sdk.LogLevel
import io.galva.sdk.Configuration
import io.galva.sdk.Environment
import io.galva.sample.perf.PerfProbe
import io.galva.sdk.Galva

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug builds also host a performance probe; its baseline process runs without Galva.
        val configuration = PerfProbe.galvaConfiguration(this) {
            Configuration(
                apiKey = BuildConfig.GALVA_API_KEY,
                logLevel = LogLevel.VERBOSE,
                env = Environment.Development,
                // Messages show on the visible activity; no getInAppMessage() collector needed.
                autoDisplayInAppMessages = true,
            )
        } ?: return
        Galva.configure(this, configuration)
    }
}
