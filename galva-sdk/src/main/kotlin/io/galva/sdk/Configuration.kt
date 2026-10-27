package io.galva.sdk

/** How [Galva.configure] sets up the SDK. */
public data class Configuration @JvmOverloads constructor(
    val apiKey: String,
    val logLevel: LogLevel = LogLevel.WARN,
    /** Emit `session_start` on cold start and on returning after 30+ minutes since the last one. */
    val autoTrackSessions: Boolean = true,
    val env: Environment = Environment.Production,
    /** Show in-app messages automatically on the visible activity; no `getInAppMessage()` collector needed. */
    val autoDisplayInAppMessages: Boolean = false,
    /** Receives SDK logs instead of Logcat (e.g. to forward them to Crashlytics); the level filter still applies. */
    val logSink: LogSink? = null,
)
// A blank apiKey is not rejected here: throwing from Application.onCreate would crash the app.
// Galva.configure logs an error instead and stays disabled.

/**
 * Galva backend the SDK talks to. Environments are fully isolated; an API key is only
 * valid against the environment it was issued for.
 */
public sealed class Environment(public val baseAPIUrl: String, public val baseWebviewUrl: String) {
    /** `api.galva.io` + `webview.galva.io`. Use for store releases. */
    public data object Production : Environment("https://api.galva.io/", "https://webview.galva.io/")

    /** `api.galva.dev` + `webview.galva.dev`. Use for internal / staging builds. */
    public data object Development : Environment("https://api.galva.dev/", "https://webview.galva.dev/")

    /** Proxy / on-prem setups. [webviewUrl] must end with `/`; bundles load from `<webviewUrl><version>.html`. */
    public data class Custom(val apiUrl: String, val webviewUrl: String) : Environment(apiUrl, webviewUrl)
}
