# Installation

This guide gets the Galva Android SDK into your app and verifies that it is wired up correctly. It takes about 10 minutes.

- [Requirements](#requirements)
- [1. Add the Galva Maven repository](#1-add-the-galva-maven-repository)
- [2. Add the dependencies](#2-add-the-dependencies)
- [3. Configure the SDK](#3-configure-the-sdk)
- [4. Verify the installation](#4-verify-the-installation)
- [R8 / ProGuard](#r8--proguard)
- [Using a local build of the SDK](#using-a-local-build-of-the-sdk)
- [Installation problems](#installation-problems)

---

## Requirements

| | Minimum | Why |
|---|---|---|
| `minSdk` | **24** (Android 7.0) | SDK `minSdk` |
| `compileSdk` | **36** | The AAR declares `minCompileSdk=36` |
| Android Gradle Plugin | A version that supports `compileSdk 36` (8.9.1+) | Needed for `compileSdk 36` |
| Kotlin | **2.1+** | The SDK is compiled with Kotlin 2.2; older compilers cannot read its metadata |
| JDK (build) | **17** | The SDK targets Java 17 bytecode |
| Google Play Billing | `com.android.billingclient:billing-ktx` **8.0.0+** | Required at runtime — see [step 2](#2-add-the-dependencies) |

Java-only apps are supported (see [Java](integration-guide.md#calling-the-sdk-from-java)), but the Kotlin standard library is still pulled in transitively.

---

## 1. Add the Galva Maven repository

The SDK is published to **GitHub Packages**:

```
https://maven.pkg.github.com/Galva-io/galva-android
```

GitHub Packages requires authentication for every download, even for public packages. You need:

- a GitHub username, and
- a [personal access token (classic)](https://github.com/settings/tokens) with the **`read:packages`** scope.

### Store the credentials (never commit them)

Put them in your **user-level** Gradle properties file — `~/.gradle/gradle.properties` on macOS/Linux, `%USERPROFILE%\.gradle\gradle.properties` on Windows:

```properties
gpr.user=your-github-username
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx
```

On CI, provide the same values as environment variables instead (`GPR_USER` / `GPR_KEY` below), for example from your CI secret store.

### Register the repository

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GalvaGitHubPackages"
            url = uri("https://maven.pkg.github.com/Galva-io/galva-android")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GPR_USER")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GPR_KEY")).orNull
            }
            // Only resolve Galva artifacts from this repository.
            content { includeGroup("io.galva.sdk") }
        }
    }
}
```

<details>
<summary>Groovy (<code>settings.gradle</code>)</summary>

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GalvaGitHubPackages"
            url = uri("https://maven.pkg.github.com/Galva-io/galva-android")
            credentials {
                username = settings.providers.gradleProperty("gpr.user")
                        .orElse(settings.providers.environmentVariable("GPR_USER")).getOrNull()
                password = settings.providers.gradleProperty("gpr.key")
                        .orElse(settings.providers.environmentVariable("GPR_KEY")).getOrNull()
            }
            content { includeGroup("io.galva.sdk") }
        }
    }
}
```

</details>

> If your project still declares repositories in the root `build.gradle(.kts)` (`allprojects { repositories { … } }`), add the same `maven { … }` block there instead.

---

## 2. Add the dependencies

In your **app module** `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.galva.sdk:galva-sdk:<version>")

    // Required: the SDK compiles against Play Billing but does not bundle it.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
}
```

Replace `<version>` with the version you were given (or the latest one listed under the repository's **Packages** tab on GitHub, e.g. `1.2.0-SNAPSHOT`).

`galva-sdk` is the only artifact you declare. It brings the internal modules (`core`, `identity`, `network`, `inapp-message`, `billing`, …) in transitively — do not add them yourself.

**Why Play Billing is a separate dependency.** Galva drives purchases started from in-app messages through Play Billing, so it must be on the runtime classpath. Leaving it to your app means you control the exact version. If your app already depends on Play Billing directly or through another library (for example RevenueCat), make sure the resolved version is **8.0.0 or newer**:

```bash
./gradlew :app:dependencyInsight --dependency com.android.billingclient --configuration releaseRuntimeClasspath
```

### Permissions

The SDK's manifest merges `INTERNET` and `ACCESS_NETWORK_STATE` into your app automatically. If you use [push notifications](integration-guide.md#push-notifications), your app must also request `POST_NOTIFICATIONS` at runtime on Android 13+ — the SDK never prompts on your behalf.

---

## 3. Configure the SDK

Configure Galva **once**, from `Application.onCreate()`, on the main thread:

```kotlin
import android.app.Application
import io.galva.common.logger.LogLevel
import io.galva.core.protocol.Configuration
import io.galva.core.protocol.Environment
import io.galva.sdk.Galva

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

Register the class in `AndroidManifest.xml` if you don't have a custom `Application` yet:

```xml
<application
    android:name=".MyApp"
    ... >
</application>
```

> **Match `env` to the API key.** The default is `Environment.Production`; use `Environment.Development` together with your development key for internal builds. A key only works against the environment it was issued for.

### Keep the API key out of source control

A common pattern is to read the key from an untracked `local.properties` (or a CI secret) and expose it through `BuildConfig`:

```kotlin
// app/build.gradle.kts
import java.util.Properties

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    buildFeatures { buildConfig = true }
    defaultConfig {
        val key = localProps.getProperty("GALVA_API_KEY") ?: System.getenv("GALVA_API_KEY") ?: ""
        buildConfigField("String", "GALVA_API_KEY", "\"$key\"")
    }
}
```

Make sure `local.properties` is listed in `.gitignore`.

---

## 4. Verify the installation

1. Build and run a **debug** build with `logLevel = LogLevel.DEBUG`.
2. Filter Logcat by the tag **`Galva`**:

   ```bash
   adb logcat -s Galva
   ```

3. You should see the `sdk/initialize` request and the `identities/communications` poll complete with HTTP `2xx`. On the very first launch an `identities/batchCollect` request (the anonymous profile) follows within about 10 seconds.
4. Call `Galva.instance.identify("test-user")` somewhere in the app and confirm another `identities/batchCollect` request goes out shortly after.

If nothing shows up, see [Installation problems](#installation-problems) and the [troubleshooting guide](troubleshooting.md).

Next: follow the [integration guide](integration-guide.md) to identify users, render in-app messages, handle deep links and push.

---

## R8 / ProGuard

No configuration is needed in the common case. If a minified release build fails at runtime with errors such as `SerializationException: Serializer for class '…' is not found`, or in-app messages open but stay blank / never react to taps, add these rules to your app's `proguard-rules.pro`:

```proguard
# Galva SDK
-keep class io.galva.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
```

---

## Using a local build of the SDK

To test an unreleased change of the SDK in your app:

```bash
# in the galva-android checkout
./gradlew publishToMavenLocal
```

Then add `mavenLocal()` **before** the GitHub Packages repository in your app's `settings.gradle.kts` and depend on the version set by `VERSION_NAME` in the SDK's `gradle.properties`:

```kotlin
repositories {
    mavenLocal()
    google()
    mavenCentral()
    // ...
}
```

Remove `mavenLocal()` again when you are done — it is easy to ship a stale local build by accident.

---

## Installation problems

| Symptom | Fix |
|---|---|
| `Could not GET 'https://maven.pkg.github.com/…'. Received status code 401` | Credentials are missing or wrong. Check `gpr.user` / `gpr.key` and that the token has the `read:packages` scope. |
| `Could not find io.galva.sdk:galva-sdk:<version>` | The version does not exist, or the repository block is missing / has a `content { }` filter that excludes `io.galva.sdk`. |
| `Dependency 'io.galva.sdk:…' requires libraries and applications that depend on it to compile against version 36 or later` | Raise `compileSdk` to 36. |
| `Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.2.0, expected version is 1.9.0` | Upgrade your project's Kotlin Gradle plugin to 2.1 or newer. |
| `NoClassDefFoundError: com/android/billingclient/api/…` at startup | Add `com.android.billingclient:billing-ktx:8.0.0` (or newer) to your app. |
| Logcat shows `Detected Play Billing version …, but Galva SDK requires 8.0+` | Another dependency forces an older Play Billing. Use `dependencyInsight` (above) and align it to 8.0.0+. |
| `IllegalStateException: Galva not configured` | A Galva API was used before `Galva.configure(...)`. Configure from `Application.onCreate()`. |
