package io.galva.sample.e2e

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/**
 * Brings up the mock backend before the app process creates its Application, then swaps in
 * [E2EApplication] so the SDK is configured against the mock instead of a real environment.
 */
class E2ETestRunner : AndroidJUnitRunner() {
    /** Called before Application.onCreate, so the app process knows it is a performance run. */
    override fun onCreate(arguments: Bundle?) {
        performanceRun = arguments?.getString(PERF_ARGUMENT) == "true"
        super.onCreate(arguments)
    }

    override fun newApplication(cl: ClassLoader, className: String, appContext: Context): Application {
        clearPersistedState(appContext)
        val bundle = context.assets.open(BUNDLE_ASSET).bufferedReader().use { it.readText() }
        MockBackend.start(bundle)
        return super.newApplication(cl, E2EApplication::class.java.name, appContext)
    }

    /**
     * Starts every run from a fresh install state (no identity, no session, no queued events), even
     * when an earlier run was aborted before Gradle could uninstall the app. Nothing has opened these
     * files yet: content providers and Application.onCreate run after this.
     */
    private fun clearPersistedState(appContext: Context) {
        val dataDir = File(appContext.applicationInfo.dataDir)
        listOf("shared_prefs", "databases", "files", "no_backup").forEach { File(dataDir, it).deleteRecursively() }
    }

    companion object {
        private const val BUNDLE_ASSET = "e2e_bundle.html"
        private const val PERF_ARGUMENT = "galvaPerf"

        /**
         * True when run with `-e galvaPerf true` (scripts/perf.sh): the footprint suite alone. This
         * process then leaves Galva unconfigured, so the probe processes it measures share no SDK
         * state with it.
         */
        @Volatile
        var performanceRun: Boolean = false
            private set
    }
}
