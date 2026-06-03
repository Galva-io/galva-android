# Galva Android SDK

A production-grade SDK for Android apps providing identity management, in-app messaging, billing, and event tracking — all behind a single, simple API.

[![Maven Central](https://img.shields.io/maven-central/v/io.galva/sdk.svg)](https://central.sonatype.com/artifact/io.galva/sdk)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg)](https://android-arsenal.com/api?level=21)

---

## Features

- 🆔 **Identity** — Anonymous and identified user tracking with persistent storage
- 💬 **In-app messaging** — WebView-based messages with bundle versioning and edge-to-edge UI
- 💳 **Billing** — Play Billing integration with offline-cached product catalog
- 📊 **Event tracking** — Reliable queue-based event delivery with exponential backoff
- 🔐 **Offline-first** — All data persists locally; recovers gracefully from network failures
- ⚡ **Lightweight** — Single artifact, minimal transitive dependencies

---

## Installation

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.galva:sdk:1.0.0")
    //Galva SDK requires 8.0+
    implementation("com.android.billingclient:billing-ktx:8.0.0")
}
```

Or in `build.gradle`:

```groovy
dependencies {
    implementation 'io.galva:sdk:1.0.0'
    implementation 'com.android.billingclient:billing-ktx:8.0.0'
}
```

**Requirements:**
- Android API 24+ (7.0+)
- Kotlin 1.9+
---

## Quick start

### 1. Configure on app startup

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Galva.configure(
            context = this,
            configuration = Configuration(
                apiKey = "your-api-key",
                logLevel = LogLevel.WARN,
            ),
        )
    }
}
```

Register in your `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ...>
</application>
```

### 2. Identify users

```kotlin
// Identify by user ID
Galva.instance.identify("user-42")

// Identify with email
Galva.instance.identify("user-42", "user@example.com")

// Identify with email and obfuscated account ID (for Play Billing)
Galva.instance.identify("user-42", "user@example.com", "obfuscated-account-id")

// Update profile properties
Galva.instance.updateProperties(
    ProfileProperty("plan", "premium"),
    ProfileProperty("signup_date", "2026-01-15"),
)

// Log out
Galva.instance.logout()
```

### 3. Show in-app messages

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                Galva.instance.getInAppMessage().collect { message ->
                    Galva.instance.showMessage(this@MainActivity, message)
                }
            }
        }
    }
}
```

---

## Configuration

The `Configuration` class accepts:

| Property | Type | Description |
|---|---|---|
| `apiKey` | `String` | Your Galva API key (required) |
| `logLevel` | `LogLevel` | Log verbosity: `NONE`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `VERBOSE` |
| `baseUrl` | `String?` | Override the default API endpoint (optional) |

```kotlin
Configuration(
    apiKey = "your-api-key",
    logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN,
)
```

---

## API reference

### `Galva.instance`

The singleton entry point. All operations go through `Galva.instance`.

#### Identity

```kotlin
fun identify(userId: String, email: String?, obfuscatedAccountId: String? = null)
fun updateProperties(vararg properties: ProfileProperty)
fun logout()

val currentUserId: String       // returns userId or anonymousId
val isAnonymous: Boolean
val obfuscatedAccountId: String?  // for Play Billing integration
```

#### In-app messaging

```kotlin
fun getInAppMessage(): Flow<Message>
fun showMessage(activity: Activity, message: Message)
```

#### Billing

```kotlin
val billing: BillingManager
```

#### Logging control

```kotlin
fun setLogLevel(level: LogLevel)
fun setEnableLogging(enabled: Boolean)
```

---

## Troubleshooting

### "Galva not configured" error

Make sure `Galva.configure()` is called in your `Application.onCreate()` **before** any other SDK call.

### Catalog is empty after `refresh()`

The SDK queries Play Billing for product details based on IDs from your backend. Verify:
1. Your products are configured in Google Play Console
2. Product IDs from your API match those in Play Console
3. The test device has a Google account with billing access
4. For test builds, the app is signed and uploaded to Internal Testing

Check logs with `LogLevel.DEBUG` to trace the fetch → resolve → persist flow.

### Purchase flow never completes

Common causes:
- Test account not added to "License testers" in Play Console
- App not uploaded to Internal Testing track
- `BillingClient.queryProductDetailsAsync` returns empty for unpublished products

Enable verbose logging to inspect `BillingClient` response codes.

### In-app message not showing

The IAM system fetches messages on a 5-second poll plus app foreground events. Verify:
- Your backend is returning messages for the current user
- WebView bundle version matches the SDK's expected version
- Network connectivity is available on first message resolution

---

## Module structure

```
:build-logic           ← Gradle configuration and dependency management
:common                ← Logger, Configuration, shared utilities
:core                  ← Core SDK models
:local-storage         ← key value secured storage implementation
:identity              ← IdentityManager interface
:operation-queue       ← OperationManager interface
:network               ← HttpClient, service interfaces
:inapp-message         ← InAppMessagingManager interface, Message types
:billing               ← BillingManager interface, catalog types
:galva-sdk             ← Default implementations + Galva facade (published artifact)
```

Consumers interact only with `:galva-sdk` via the `io.galva:sdk:1.0.0` Maven coordinate.

---

## License

```
Copyright 2026 Galva

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style, and PR guidelines.

## Support

- 📚 [Documentation](https://docs.galva.dev)
- 🐛 [Report a bug](https://github.com/Galva-io/galva-android/issues)
- 💬 [Discussions](https://github.com/Galva-io/galva-android/discussions)
- 📧 support-dev@galva.io
