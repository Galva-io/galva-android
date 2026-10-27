# Galva Android SDK

The Android client for [Galva](https://galva.io) — retention infrastructure for subscription apps.

Add the SDK, identify your users and render the in-app messages Galva sends; Galva decides who needs a nudge and when (failed renewals, trials that don't convert, users about to churn) and delivers it in-app, by push or by email.

| | |
|---|---|
| **Min SDK** | 24 (Android 7.0) |
| **Compile SDK** | 36 |
| **Kotlin** | 2.1+ (Java callers supported) |
| **Distribution** | GitHub Packages — `io.galva.sdk:galva-sdk` |
| **Required peer dependency** | Google Play Billing `billing-ktx` 8.0.0+ |
| **Threading** | Every public call returns immediately; work runs on a background queue |
| **Footprint** | Public calls return in microseconds; under 8 MB of extra memory at idle (checked by `scripts/perf.sh`) |
| **Safety** | Never throws at your app: misconfiguration and unexpected errors are logged, not raised |

---

## Features

- **Identity** — anonymous install id from the first launch, linked to your user id on `identify()`; emails are validated on the device.
- **Events** — `track()` with loose or typed attributes, persisted and batched.
- **Sessions** — automatic `session_start` with the same 30-minute rule as iOS.
- **Privacy** — `setOptOut()` stops server-bound tracking and purges pending events.
- **User properties** — typed built-in traits plus free-form custom properties.
- **In-app messages** — server-driven, WebView-based messages with a cached bundle and purchases from inside the message; one-line automatic display.
- **Deep links** — open a targeted message from an email or push; links that arrive before sign-in are replayed after `identify()`.
- **Push notifications** — FCM token registration, rendering, and `$gv_notification_presented` / `tapped` / `dismissed` events for Galva notifications.
- **Billing** — Play Billing integration with an offline-cached product catalog; purchases can run through your own billing layer (`GalvaPurchaseController`) or RevenueCat ([`galva-revenuecat`](https://github.com/Galva-io/galva-android-revenuecat)).
- **Offline-first delivery** — updates are persisted to SQLite and retried with backoff.

---

## Quick start

**1. Add the repository and dependencies** — full details, including credentials, in [docs/installation.md](docs/installation.md):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Galva-io/galva-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull   // token with read:packages
            }
        }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.galva.sdk:galva-sdk:<version>")
    implementation("com.android.billingclient:billing-ktx:8.0.0")
}
```

**2. Configure once** in your `Application`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Galva.configure(
            context = this,
            configuration = Configuration(
                apiKey = BuildConfig.GALVA_API_KEY,
                env = if (BuildConfig.DEBUG) Environment.Development else Environment.Production,
                logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN,
            ),
        )
    }
}
```

**3. Identify the user** after sign-in (and on start-up for restored sessions):

```kotlin
Galva.instance.identify("user_42", email = "jane@example.com")
Galva.instance.updateProperties(ProfileProperty.Custom("plan_tier", "pro"))
Galva.instance.track("Purchase", mapOf("sku" to "pro_yearly", "price" to 9.99))

// on sign-out
Galva.instance.logout()
```

**4. Render in-app messages** — either add `autoDisplayInAppMessages = true` to the `Configuration` above, or collect them yourself from your main activity:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        Galva.instance.getInAppMessage().collect { message ->
            Galva.instance.showMessage(this@MainActivity, message)
        }
    }
}
```

That is the core integration. Deep links and push take a few more lines each — see the [integration guide](docs/integration-guide.md).

---

## Documentation

| Guide | What's inside |
|---|---|
| [Installation](docs/installation.md) | Requirements, GitHub Packages credentials, dependencies, configuration, verifying the setup, R8 |
| [Integration guide](docs/integration-guide.md) | Configuration reference, identity, properties, in-app messages (Views + Compose), deep links, FCM push, billing, logging, Java |
| [Troubleshooting](docs/troubleshooting.md) | Common problems and how to diagnose them |

---

## API at a glance

All calls go through `Galva.instance` (`io.galva.sdk.Galva`).

```kotlin
// Setup
Galva.configure(context, configuration, integrations = emptyList())   // static, once, from Application.onCreate()
Galva.sdkVersion: String

// Identity
fun identify(userId: String, email: String? = null, obfuscatedAccountId: String? = null)
fun updateProperties(vararg properties: ProfileProperty)
fun logout()
val currentUserId: String          // userId, or the anonymous id
val isAnonymous: Boolean
val obfuscatedAccountId: String

// Events
fun track(eventName: String, attributes: Map<String, Any?>? = null)
fun track(event: AppEvent)

// Privacy
fun setOptOut(optedOut: Boolean)
val isOptedOut: Boolean

// In-app messages
fun getInAppMessage(): Flow<Message>
fun showMessage(activity: Activity, message: Message)
fun checkForMessages()             // poll now, outside the foreground cadence

// Deep links
fun handleOpenURL(uri: Uri): Boolean        // true when it is a Galva link

// Push (FCM by default; HMS and ADM on devices without Google Play services)
fun setPushToken(token: String, provider: PushProvider = PushProvider.FCM)
fun clearPushToken()
fun isGalvaNotification(data: Map<String, String>): Boolean
fun handlePush(data: Map<String, String>)

// Billing
fun setPurchaseController(controller: GalvaPurchaseController?)   // run message purchases through your billing layer

// Logging
fun setLogLevel(level: LogLevel)
fun setEnableLogging(enabled: Boolean)
fun setLogger(sink: LogSink)
```

This is the whole public API (plus `AppEvent`, `Configuration`, `Environment`, `ProfileProperty`, `LogLevel`, `LogSink`, `Message`, `WorkflowType`, the billing models, `GalvaIntegration` and the purchase controller types: `GalvaPurchaseController`, `GalvaPurchaseRequest`, `GalvaPurchaseResult`, `GalvaTransaction`, `GalvaPurchaseException`). Building an integration package takes the `@GalvaIntegrationApi` opt-in. Everything else in the SDK's modules is marked `@InternalGalvaApi` and does not compile when used from an app.

---

## Sample app

The [`app`](app) module is a minimal integration. To run it, add your development key to `app/local.properties` (this file must not be committed):

```properties
GALVA_API_KEY=pk_test_xxxxxxxx
```

```bash
./gradlew :app:installDebug
```

---

## Project structure

```
:galva-sdk       ← the whole public API (io.galva.sdk) + default implementations — the artifact apps depend on
:core            ← operation contracts
:identity        ← identity model and store
:operation-queue ← persistent batching queue (SQLite)
:network         ← HTTP client and API services
:inapp-message   ← in-app message activity, JS bridge, bundle cache
:billing         ← billing contracts, catalog and entitlement stores
:local-storage   ← encrypted key-value storage
:common          ← logger, utilities
:build-logic     ← Gradle convention plugins (build, publishing)
:app             ← sample app
```

Apps depend only on `io.galva.sdk:galva-sdk` and compile only against it. The other modules are published as its runtime dependencies, so their classes are not on the app's compile classpath.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development setup, conventions and release process.

## Support

- 🐛 [Report a bug](https://github.com/Galva-io/galva-android/issues)
- 📧 support-dev@galva.io

## License

MIT — see [LICENSE](LICENSE).
