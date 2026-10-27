# Galva Android SDK

Connects your Android app to [Galva](https://galva.io): Galva decides which subscribers need a nudge (failed renewals, trials about to end, users about to churn) and reaches them with in-app messages, push notifications and emails.

**Requirements:** minSdk 24, compileSdk 36, Kotlin 2.1+ (Java works too), Google Play Billing 8.0.0+.

- [Quick start](#quick-start)
- [In-app messages](#in-app-messages)
- [Deep links](#deep-links)
- [Push notifications](#push-notifications)

---

## Quick start

### 1. Install

The SDK is published on GitHub Packages, which needs a GitHub username and a [personal access token (classic)](https://github.com/settings/tokens) with the `read:packages` scope. Put them in your user-level `~/.gradle/gradle.properties`, never in the project:

```properties
gpr.user=your-github-username
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx
```

Add the repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Galva-io/galva-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

Add the dependencies in `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.galva.sdk:galva-sdk:1.3.0-SNAPSHOT")
    // Required: purchases inside Galva's messages run through Play Billing 8.0.0 or newer.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
}
```

Using RevenueCat? Depend on [`io.galva.sdk:galva-revenuecat`](https://github.com/Galva-io/galva-android-revenuecat) instead, and follow its README.

### 2. Configure

Configure Galva once, in your `Application`:

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
            this,
            Configuration(
                apiKey = BuildConfig.GALVA_API_KEY, // your app's API key from the Galva dashboard
                env = if (BuildConfig.DEBUG) Environment.Development else Environment.Production,
                logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN,
            ),
        )
    }
}
```

```xml
<!-- AndroidManifest.xml -->
<application
    android:name=".MyApp"
    ... >
```

- An API key only works against the environment it was issued for: your development key with `Environment.Development`, your production key with `Environment.Production`.
- Tell Galva who the user is: `Galva.instance.identify(userId)` after sign-in and on every start with a restored session, and `Galva.instance.logout()` on sign-out.
- To check the setup, run a debug build and filter Logcat on the `Galva` tag: the `sdk/initialize` request should succeed.

Every type you use is in `io.galva.sdk`. All calls return immediately, and the SDK never throws at your app: problems are logged instead.

---

## In-app messages

Galva checks for a message when the app starts and every time it returns to the foreground. Observe the messages from your main activity and pass each one to `showMessage`, which opens it full screen. `observeInAppMessages` calls you on the main thread while the activity is resumed, and stops by itself when it is destroyed:

```kotlin
import io.galva.sdk.Galva

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Galva.instance.observeInAppMessages(this) { message ->
            Galva.instance.showMessage(this, message)
        }
    }
}
```

```java
import io.galva.sdk.Galva;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Galva.getInstance().observeInAppMessages(this, message ->
                Galva.getInstance().showMessage(this, message));
    }
}
```

In Kotlin you can also collect the same messages as a `Flow`, `Galva.instance.getInAppMessage()`. With Jetpack Compose, call this once near the root of your UI:

```kotlin
import androidx.activity.compose.LocalActivity            // activity-compose 1.10+
import androidx.lifecycle.compose.LocalLifecycleOwner      // lifecycle-runtime-compose 2.8+
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun GalvaInAppMessages() {
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(activity, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            Galva.instance.getInAppMessage().collect { message ->
                Galva.instance.showMessage(activity, message)
            }
        }
    }
}
```

- **Choose when messages appear.** In the listener, skip or delay `showMessage`, for example during onboarding or checkout. `message.getWorkflowType()` (`message.workflowType` in Kotlin) says which workflow sent it.
- **Check right away** with `Galva.instance.checkForMessages()`, for example after a user action that should trigger a workflow.
- **No control needed?** Set `autoDisplayInAppMessages = true` in `Configuration` instead: each message then shows on the visible activity, with no listener. Use one approach or the other.

---

## Deep links

Galva's emails and pushes link straight to a message in your app, with your app's scheme (`gv-…`, shown in the Galva dashboard).

**1. Declare the scheme** on the activity that receives the links:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <!-- Your scheme from the Galva dashboard -->
        <data android:scheme="gv-xYkIIqntk5UIIkHN" />
    </intent-filter>
</activity>
```

**2. Pass the links to Galva**, both the one that launched the activity and later ones:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Not on recreation (e.g. rotation): the link was already handled.
    if (savedInstanceState == null) intent?.data?.let(::openLink)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let(::openLink)
}

private fun openLink(uri: Uri) {
    if (Galva.instance.handleOpenURL(uri)) return // a Galva link: the SDK opens the message
    // ...otherwise, one of your own links
}
```

`handleOpenURL` returns `false` for links that aren't Galva's, so you can route your own after it. A link that arrives before `identify()` (for example a cold start from an email) is kept and opened right after `identify()`.

---

## Push notifications

Galva sends pushes through Firebase Cloud Messaging (FCM). Your app keeps its own `FirebaseMessagingService`: it gives Galva the device token and hands it Galva's pushes, and the SDK shows and tracks those notifications.

**1. Set up FCM** by following Firebase's [Android setup](https://firebase.google.com/docs/cloud-messaging/android/client): your `google-services.json`, the Google services Gradle plugin, and `com.google.firebase:firebase-messaging`.

**2. Register the token and forward Galva's pushes** from your messaging service:

```kotlin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.galva.sdk.Galva

class AppMessagingService : FirebaseMessagingService() {

    // FCM issued a new token: first launch, reinstall, or rotation.
    override fun onNewToken(token: String) {
        Galva.instance.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (Galva.instance.isGalvaNotification(message.data)) {
            Galva.instance.handlePush(message.data) // shows the notification and tracks it
            return
        }
        // ...your own pushes
    }
}
```

```xml
<!-- AndroidManifest.xml, inside <application> -->
<service
    android:name=".AppMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

**3. Also send the current token** after `Galva.configure(...)` and after each sign-in. `onNewToken` only fires when the token changes, and `logout()` removes the token from the signed-out user:

```kotlin
import com.google.firebase.messaging.FirebaseMessaging

FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    Galva.instance.setPushToken(token)
}
```

Sending the same token again is free: the SDK only registers a token that changed.

**4. Ask for the notification permission.** On Android 13+, declare `android.permission.POST_NOTIFICATIONS` in the manifest and request it at runtime. Galva never prompts; without the permission its notifications are not shown.

**5. Set the notification icon** (recommended), a monochrome drawable. Without it, Galva falls back to your launcher icon, which shows as a white square on most devices:

```xml
<application ...>
    <meta-data
        android:name="io.galva.sdk.default_notification_icon"
        android:resource="@drawable/ic_stat_notification" />
</application>
```

Tapping a Galva notification opens your launcher activity. To stop pushes to this device, for example when the user turns notifications off in your settings, call `Galva.instance.clearPushToken()`. Huawei devices without Google Play services and Amazon Fire devices use their own push service: see [Push notifications](docs/integration-guide.md#6-devices-without-google-play-services) in the integration guide.

---

**More:** [integration guide](docs/integration-guide.md) (user properties, events, sessions, opt-out, billing and RevenueCat, logging, Java) · [installation details](docs/installation.md) (credentials on CI, R8, upgrading from 1.2) · [troubleshooting](docs/troubleshooting.md) · [contributing](CONTRIBUTING.md) · License: MIT
