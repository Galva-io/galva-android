# Installation

This guide gets the Galva Android SDK into your app and verifies that it is wired up correctly. It takes about 10 minutes.

- [Requirements](#requirements)
- [1. Add the Galva Maven repository](#1-add-the-galva-maven-repository)
- [2. Add the dependencies](#2-add-the-dependencies)
- [3. Configure the SDK](#3-configure-the-sdk)
- [4. Verify the installation](#4-verify-the-installation)
- [R8 / ProGuard](#r8--proguard)
- [Using a local build of the SDK](#using-a-local-build-of-the-sdk)
- [Upgrading from 1.2](#upgrading-from-12)
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

The SDK is published to [**Maven Central**](https://central.sonatype.com/artifact/io.galva.sdk/galva-sdk), which needs no credentials. New Android projects already list it; check your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### Pre-release snapshots

`-SNAPSHOT` versions are published to Maven Central's snapshot repository instead, and are replaced by each new build of the same version. To try one, add that repository, limited to Galva's artifacts:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "CentralPortalSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            content { includeGroup("io.galva.sdk") }
            mavenContent { snapshotsOnly() }
        }
    }
}
```

Snapshots are for testing only: they are removed after about 90 days, so ship releases.

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

Replace `<version>` with the latest release shown on [Maven Central](https://central.sonatype.com/artifact/io.galva.sdk/galva-sdk), e.g. `1.3.0`.

`galva-sdk` is the only artifact you declare. It brings the SDK's internal modules (`core`, `identity`, `network`, `inapp-message`, `billing`, …) as runtime dependencies; don't add them yourself. Your code compiles against `galva-sdk` alone: every type you use is in `io.galva.sdk`.

**Using RevenueCat?** Declare [`io.galva.sdk:galva-android-revenuecat`](https://github.com/Galva-io/galva-android-revenuecat) instead of `galva-sdk` (it brings it). Purchases from Galva's messages then go through RevenueCat, and Galva follows RevenueCat's user, so you don't call `identify` yourself. Its README covers the setup.

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
import io.galva.sdk.Configuration
import io.galva.sdk.Environment
import io.galva.sdk.Galva
import io.galva.sdk.LogLevel

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

Then add `mavenLocal()` **first** in your app's `settings.gradle.kts` repositories and depend on the version set by `VERSION_NAME` in the SDK's `gradle.properties`:

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

## Upgrading from 1.2

1.3.0 puts every type apps use in `io.galva.sdk`; the SDK's other modules are no longer on your compile classpath. Update the imports, and replace the few removed members:

| 1.2 | 1.3 |
|---|---|
| `io.galva.core.protocol.Configuration`, `io.galva.core.protocol.Environment` | `io.galva.sdk.Configuration`, `io.galva.sdk.Environment` |
| `io.galva.core.protocol.identity.ProfileProperty` | `io.galva.sdk.ProfileProperty` |
| `io.galva.common.logger.LogLevel`, `io.galva.common.logger.LogSink` | `io.galva.sdk.LogLevel`, `io.galva.sdk.LogSink` |
| `io.galva.iam.Message`, `io.galva.iam.WorkflowType` | `io.galva.sdk.Message`, `io.galva.sdk.WorkflowType` |
| `Galva.instance.billingManager` | Removed. Purchases inside messages need no code; to run them through your own billing layer, install a [purchase controller](integration-guide.md#your-own-billing-layer-purchase-controllers). |
| `ProfileProperty.propertyValue` | Removed. Read the property's own field, e.g. `ProfileProperty.Custom.value`. |
| `WorkflowType.wireValue`, `WorkflowType.fromWireValue` | Removed. |

Everything else keeps working as is. `setPushToken(token)` still registers an FCM token; its new optional `PushProvider` argument is for Huawei and Amazon devices.

---

## Installation problems

| Symptom | Fix |
|---|---|
| `Could not find io.galva.sdk:galva-sdk:<version>` | The version is not on [Maven Central](https://central.sonatype.com/artifact/io.galva.sdk/galva-sdk), or `mavenCentral()` is missing from your repositories. A `-SNAPSHOT` version also needs the [snapshot repository](#pre-release-snapshots). A release that was just published can take a little while to become downloadable. |
| `Unresolved reference 'core'` / `'common'` / `'iam'` in a Galva import, or `Cannot access class 'Configuration'` | Code written for 1.2: the types moved to `io.galva.sdk`. See [Upgrading from 1.2](#upgrading-from-12). |
| `Dependency 'io.galva.sdk:…' requires libraries and applications that depend on it to compile against version 36 or later` | Raise `compileSdk` to 36. |
| `Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.2.0, expected version is 1.9.0` | Upgrade your project's Kotlin Gradle plugin to 2.1 or newer. |
| Logcat shows `Galva SDK requires Play Billing 8.0+, but the app has …` | Purchases inside messages fail until you add `com.android.billingclient:billing-ktx:8.0.0` (or newer), or align the version another dependency forces (`dependencyInsight`, above). The rest of the SDK keeps working. |
| Logcat shows `Galva.configure() ignored: apiKey is blank` | The `apiKey` passed to `Configuration` is empty, often a build property missing on CI. Galva stays off; your app is not affected. |
