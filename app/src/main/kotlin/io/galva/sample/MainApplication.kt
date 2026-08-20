package io.galva.sample

import android.app.Application
import android.util.Log
import io.galva.common.logger.LogLevel
import io.galva.core.protocol.Configuration
import io.galva.sdk.Galva

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.e("MainApplication", "Initializing Galva SDK with API Key: ${BuildConfig.GALVA_API_KEY}")
        Galva.configure(
            this, Configuration(
                apiKey = BuildConfig.GALVA_API_KEY,
                logLevel = LogLevel.VERBOSE
            )
        )
    }
}