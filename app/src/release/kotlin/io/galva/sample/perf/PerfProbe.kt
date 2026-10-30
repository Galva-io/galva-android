package io.galva.sample.perf

import android.app.Application
import io.galva.sdk.Configuration

/** Release builds have no performance probe (see src/debug): Galva is always configured. */
object PerfProbe {
    fun galvaConfiguration(app: Application, default: () -> Configuration): Configuration? = default()
}
