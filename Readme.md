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

---

## Features

- **Identity** — anonymous install id from the first launch, linked to your user id on `identify()`.
- **User properties** — typed built-in traits plus free-form custom properties.
- **In-app messages** — server-driven, WebView-based messages with a cached bundle and purchases from inside the message.
- **Deep links** — open a targeted message from an email or push; links that arrive before sign-in are replayed after `identify()`.
- **Push notifications** — FCM token registration, rendering and tap / dismiss tracking for Galva notifications.
- **Billing** — Play Billing integration with an offline-cached product catalog.
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

// on sign-out
Galva.instance.logout()
```

**4. Render in-app messages** from your main activity:

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
Galva.configure(context, configuration)          // static, once, from Application.onCreate()

// Identity
fun identify(userId: String, email: String? = null, obfuscatedAccountId: String? = null)
fun updateProperties(vararg properties: ProfileProperty)
fun logout()
val currentUserId: String          // userId, or the anonymous id
val isAnonymous: Boolean
val obfuscatedAccountId: String

// In-app messages
fun getInAppMessage(): Flow<Message>
fun showMessage(activity: Activity, message: Message)

// Deep links
fun handleOpenURL(uri: Uri)

// Push (FCM)
fun setPushToken(token: String)
fun clearPushToken()
fun isGalvaNotification(data: Map<String, String>): Boolean
fun handlePush(data: Map<String, String>)

// Billing
val billingManager: BillingManager

// Logging
fun setLogLevel(level: LogLevel)
fun setEnableLogging(enabled: Boolean)
```

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
:galva-sdk       ← public facade (Galva) + default implementations — the artifact apps depend on
:core            ← configuration, environment, operation contracts
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

Apps depend only on `io.galva.sdk:galva-sdk`; the other modules are published as its transitive dependencies.

---

## Support

- 🐛 [Report a bug](https://github.com/Galva-io/galva-android/issues)
- 📧 support-dev@galva.io

## License

Licensed under the Apache License, Version 2.0 (as declared in the published POM).
