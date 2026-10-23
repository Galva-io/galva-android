package io.galva.sample

import android.app.Application
import io.galva.common.logger.LogLevel
import io.galva.core.protocol.Configuration
import io.galva.core.protocol.Environment
import io.galva.sdk.Galva

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Galva.configure(
            this, Configuration(
                apiKey = BuildConfig.GALVA_API_KEY,
                logLevel = LogLevel.VERBOSE,
                env = Environment.Development,
            )
        )
    }
}
