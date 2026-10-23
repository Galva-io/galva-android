# Integration guide

Everything after [installation](installation.md): identifying users, rendering in-app messages, deep links, push notifications, billing and logging.

- [How it works](#how-it-works)
- [Configuration reference](#configuration-reference)
- [Identify users](#identify-users)
- [User properties](#user-properties)
- [Sessions](#sessions)
- [Privacy and opt-out](#privacy-and-opt-out)
- [In-app messages](#in-app-messages)
- [Deep links](#deep-links)
- [Push notifications](#push-notifications)
- [Billing](#billing)
- [Logging](#logging)
- [Calling the SDK from Java](#calling-the-sdk-from-java)
- [Integration checklist](#integration-checklist)

---

## How it works

```
Your app                         Galva SDK                              Galva backend
────────                         ─────────                              ─────────────
Galva.configure(...)      ───▶   anonymous id, sdk/initialize    ───▶   resolves your app from the API key
identify / updateProperties ─▶   persisted to SQLite, batched    ───▶   ingests, attributes
                                 ◀── pending in-app message ─────────   workflow decides who / when
getInAppMessage().collect ─▶     showMessage() renders a WebView
```

- Every public call is **fire-and-forget**: it returns immediately and the work runs on a background queue, in the order you made the calls.
- Profile updates are written to disk before upload, so they survive process death and offline periods and are retried with backoff.
- Calls made before `configure()` (for example `identify`) are queued and processed once the SDK is configured.

The single entry point is `Galva.instance` (`io.galva.sdk.Galva`).

---

## Configuration reference

```kotlin
Galva.configure(
    context = this, // your Application
    configuration = Configuration(
        apiKey = "…",
        env = Environment.Production,
        logLevel = LogLevel.WARN,
    ),
)
```

| Property | Type | Default | Notes |
|---|---|---|---|
| `apiKey` | `String` | — (required) | Your publishable key from the Galva dashboard. Must not be blank. |
| `env` | `Environment` | `Environment.Production` | `Environment.Production` (`api.galva.io`), `Environment.Development` (`api.galva.dev`), or `Environment.Custom(apiUrl, webviewUrl)` for proxies / on-prem. Must match the API key. |
| `logLevel` | `LogLevel` | `LogLevel.WARN` | `NONE`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `VERBOSE`. |
| `autoTrackSessions` | `Boolean` | `true` | Sends `session_start` automatically — see [Sessions](#sessions). |
| `autoDisplayInAppMessages` | `Boolean` | `false` | Shows in-app messages on the visible activity without any code — see [In-app messages](#in-app-messages). |
| `logSink` | `LogSink?` | `null` (Logcat) | Receives the SDK logs instead of Logcat — see [Logging](#logging). |

Rules:

- Call `configure` **once**, from `Application.onCreate()`, on the **main thread**. Later calls are ignored with a log line.
- `configure` returns almost immediately: it only records the configuration and registers lifecycle observers; storage, billing and the in-app message pipeline are set up in the background. Calls made in the meantime are queued, and `currentUserId` / `obfuscatedAccountId` are empty until the identity has been loaded.
- Use one API key per environment; keys are not interchangeable between `Development` and `Production`.
- The API key is never written to the logs.

---

## Identify users

Before you identify anyone, Galva tracks the install with an anonymous id generated on first launch. Call `identify` as soon as you know who the user is — after sign-in, and on every app start where a session is restored:

```kotlin
Galva.instance.identify("user_42")

// Optionally with an email address
Galva.instance.identify("user_42", email = "jane@example.com")
```

Email addresses are checked on the device (one `@`, a dotted domain, no spaces) like in the iOS SDK; an invalid address is dropped and logged as a warning, and the rest of the call still applies. The same check applies to `ProfileProperty.Email`.

On sign-out:

```kotlin
Galva.instance.logout()
```

`logout()` clears the user binding and starts a fresh anonymous identity. Profile updates of the signed-out user that have not been uploaded yet are discarded, so they can never be attributed to the next user; everything recorded after `logout()` belongs to the new identity. If you registered a push token, call [`setPushToken`](#2-register-the-token) again after the next sign-in.

Synchronous reads (only valid **after** `configure`):

```kotlin
Galva.instance.isAnonymous          // true until identify() is processed
Galva.instance.currentUserId        // your userId, or the anonymous id
Galva.instance.obfuscatedAccountId  // stable per-identity id used for Play Billing attribution
```

---

## User properties

Attach traits to the current profile with `updateProperties`. Built-in keys are typed:

```kotlin
import io.galva.core.protocol.identity.ProfileProperty

Galva.instance.updateProperties(
    ProfileProperty.Email("jane@example.com"),
    ProfileProperty.FirstName("Jane"),
    ProfileProperty.LastName("Doe"),
    ProfileProperty.FullName("Jane Doe"),
)
```

Anything else goes through `Custom`:

```kotlin
Galva.instance.updateProperties(
    ProfileProperty.Custom("plan_tier", "pro"),
    ProfileProperty.Custom("habit_count", 13),
    ProfileProperty.Custom("beta_features", listOf("streaks", "widgets")),
)
```

`Custom` values may be `String`, `Number`, `Boolean`, `null`, `List` or `Map` (nested values follow the same rules). Convert other types yourself — for example dates to an ISO-8601 string or epoch millis.

The SDK also maintains a few properties for you: time zone, language and country on every identify, and the last-seen time each time the app goes to the background.

---

## Sessions

With `autoTrackSessions = true` (the default) the SDK sends a `session_start` event when the app comes to the foreground and no session was started in the last 30 minutes — the same rule as the iOS SDK. The 30-minute window is measured from the previous `session_start` and survives app restarts; there is no `session_end` event (the backend derives durations). Each event carries `device_locale`, `os_version`, `app_version` and `sdk_version`.

---

## Privacy and opt-out

Give users a switch for server-bound tracking:

```kotlin
Galva.instance.setOptOut(true)    // stop tracking
Galva.instance.isOptedOut         // synchronous read, safe on any thread
```

While opted out, `identify`, `updateProperties`, push-token registration, push interaction events and `session_start` are dropped, and events still waiting to be uploaded are deleted when switching to opted out. In-app messages keep working. The choice is stored on the device and survives restarts; `setOptOut(false)` turns tracking back on.

---

## In-app messages

Galva checks for a pending message at cold start and every time the app returns to the foreground. You decide **where** to render it; the SDK handles fetching the content, downloading and caching the web bundle, the full-screen WebView, and purchases started from the message.

How messages are picked (same rules as the iOS SDK):

- **At most one message per return to the foreground.** The backend returns messages highest-priority first and the SDK publishes the first one it has not published before; the others come up on later returns.
- **No polling while a message is on screen** or while a message opened from a deep link is loading — a deep link always wins over the foreground check.
- **A message the user has seen is not shown again** during the same app session, even if the same deep link arrives again.

### Automatic display (one line)

The simplest setup: let the SDK show each message on whatever activity is visible.

```kotlin
Galva.configure(this, Configuration(apiKey = BuildConfig.GALVA_API_KEY, autoDisplayInAppMessages = true))
```

A message that arrives while no activity of your app is visible is skipped. Use the manual setup below instead when you need to control *when* messages appear.

### Manual display

Collect the message stream while your UI is visible — usually in your main / root activity:

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.galva.sdk.Galva
import kotlinx.coroutines.launch

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

### Jetpack Compose

```kotlin
import androidx.activity.compose.LocalActivity            // androidx.activity:activity-compose 1.10+
import androidx.lifecycle.compose.LocalLifecycleOwner      // androidx.lifecycle:lifecycle-runtime-compose 2.8+

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

Call `GalvaInAppMessages()` once near the root of your UI.

### Branching by workflow

Each message says which workflow produced it, so you can gate or log per campaign:

```kotlin
Galva.instance.getInAppMessage().collect { message ->
    when (message.workflowType) {
        WorkflowType.TRIAL_RESCUE -> { /* trial-to-paid nudge */ }
        WorkflowType.PAYMENT_RECOVERY -> { /* failed renewal */ }
        WorkflowType.CANCELLATION_RECOVERY, WorkflowType.WINBACK -> { /* churn */ }
        null -> { /* broadcast / manual send, or a newer workflow type */ }
    }
    Galva.instance.showMessage(this@MainActivity, message)
}
```

`message.createdAt` holds the time the server queued the message (ISO-8601).

### Notes

- **Gate it if you need to.** Inside `collect` you can skip or delay `showMessage` (for example during onboarding or checkout). Not collecting at all means nothing is rendered.
- **Collect while your UI is visible.** Checking for messages does not depend on collectors; a message published while nothing is collecting is skipped for the rest of the app session. If your app has several top-level activities, collect in each of them (for example from a base activity) — all collectors receive the same message, and `showMessage` ignores a message that is already being shown.
- **Check on demand.** Call `Galva.instance.checkForMessages()` after a user action that should trigger a workflow (for example finishing onboarding), instead of waiting for the next return to the foreground.
- The message is shown in a full-screen activity provided by the SDK; the user closes it from inside the message or with the back button.

---

## Deep links

Emails and pushes sent by Galva can link straight into a message in your app, using a custom scheme that starts with `gv`. Your exact scheme is shown in the Galva dashboard next to your API key.

### 1. Declare the scheme

On the activity that should receive the link (usually the launcher activity):

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop">

    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="gvYOURSCHEME" />
    </intent-filter>
</activity>
```

### 2. Forward the links

Forward both the launch intent and any later intents. `handleOpenURL` returns `true` when the URI is a Galva link (scheme starting with `gv`) and `false` otherwise, so you can route your own links after it:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    intent?.data?.let(::routeLink)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let(::routeLink)
}

private fun routeLink(uri: Uri) {
    if (Galva.instance.handleOpenURL(uri)) return   // Galva took it
    myRouter.open(uri)                               // your own links
}
```

The supported route is:

```text
gvYOURSCHEME://openCommunication?communicationId=<id>
```

All query parameters of the link are passed to the message page as `window.galvaDeepLinkParams`, so a tap carries its campaign context with it.

**Deferred until identify.** A targeted message can only be opened for a known user. If the link arrives before `identify()` (for example a cold start straight from an email), the SDK keeps the most recent link and opens it automatically right after you call `identify()`.

---

## Push notifications

Galva sends push campaigns through Firebase Cloud Messaging (FCM). You keep your own `FirebaseMessagingService`; Galva needs the device token, and it renders and tracks its own notifications.

### 1. Set up FCM

Follow Firebase's [Android setup](https://firebase.google.com/docs/cloud-messaging/android/client) to add `com.google.firebase:firebase-messaging` and your `google-services.json`.

### 2. Register the token

```kotlin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.galva.sdk.Galva

class AppMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Galva.instance.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (Galva.instance.isGalvaNotification(message.data)) {
            Galva.instance.handlePush(message.data)   // shows + tracks the notification
            return
        }
        // …your own notifications
    }
}
```

```xml
<service
    android:name=".AppMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

`onNewToken` only fires when FCM issues a new token, so also hand the current token to Galva on start-up — for example right after `configure`, and again after a sign-in:

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    Galva.instance.setPushToken(token)
}
```

To stop pushes to this device (for example when the user disables notifications in your settings), call `Galva.instance.clearPushToken()`.

### 3. Notification permission

On Android 13+ your app must request `android.permission.POST_NOTIFICATIONS` (declare it in the manifest and ask at runtime). Galva never prompts; without the permission its notifications are silently skipped.

### 4. Icon and color (recommended)

Galva notifications are posted on a channel named **"Galva SDK Notifications"**. Point the SDK at a monochrome small icon and an accent color — otherwise it falls back to your launcher icon, which renders as a white square on most devices:

```xml
<application ...>
    <meta-data
        android:name="io.galva.sdk.default_notification_icon"
        android:resource="@drawable/ic_stat_notification" />
    <meta-data
        android:name="io.galva.sdk.default_notification_color"
        android:resource="@color/notification_accent" />
</application>
```

Tapping a Galva notification opens your launcher activity (the notification extras are forwarded to it); taps and dismissals are tracked automatically.

---

## Billing

Galva uses Google Play Billing to run purchases that start **inside** an in-app message (for example a win-back offer). You do not need to write any billing code for that — only add the `billing-ktx` dependency (see [installation](installation.md#2-add-the-dependencies)).

The SDK keeps a local copy of the Play catalog for the products configured in Galva, which you can read if useful:

```kotlin
lifecycleScope.launch {
    Galva.instance.billingManager.catalog.collect { catalog ->
        // products, base plans, offers and localized prices
    }
}
```

Purchases started inside a Galva message are acknowledged by Galva's backend, so you don't need to acknowledge them yourself. To unlock the purchased content, keep refreshing entitlements the way your app already does — Google recommends calling `BillingClient.queryPurchasesAsync()` from `onResume()`, which also picks up purchases your app did not start itself.

---

## Logging

```kotlin
Galva.instance.setLogLevel(LogLevel.DEBUG)   // change verbosity at runtime
Galva.instance.setEnableLogging(false)       // silence the SDK completely
```

All output goes to Logcat under the tag **`Galva`**:

```bash
adb logcat -s Galva
```

Use `LogLevel.DEBUG` while integrating and `LogLevel.WARN` (the default) or lower in production; `DEBUG` and `VERBOSE` include request URLs and payloads. At `DEBUG` the SDK also forwards the in-app message page's `console.*` output and JavaScript errors, so you can debug a message without attaching Chrome DevTools.

To send the logs to your own pipeline (Crashlytics, a file, …), pass a `LogSink` — in `Configuration(logSink = …)` or later with `setLogger`. The log level still filters what reaches it:

```kotlin
Galva.instance.setLogger { level, tag, message, throwable ->
    FirebaseCrashlytics.getInstance().log("[$level] $tag: $message")
}
```

For diagnostics screens and support reports, `Galva.sdkVersion` returns the SDK version.

---

## Calling the SDK from Java

```java
import io.galva.common.logger.LogLevel;
import io.galva.core.protocol.Configuration;
import io.galva.core.protocol.Environment;
import io.galva.sdk.Galva;

public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Configuration configuration = new Configuration(
                BuildConfig.GALVA_API_KEY,
                BuildConfig.DEBUG ? LogLevel.DEBUG : LogLevel.WARN,
                true,
                BuildConfig.DEBUG ? Environment.Development.INSTANCE : Environment.Production.INSTANCE
        );
        // Shorter forms work too, e.g. new Configuration(BuildConfig.GALVA_API_KEY)
        Galva.configure(this, configuration);
    }
}

// elsewhere
Galva.getInstance().identify("user_42");
Galva.getInstance().identify("user_42", "jane@example.com");
Galva.getInstance().logout();
String version = Galva.getSdkVersion();
```

Collecting `getInAppMessage()` requires a coroutine; from Java, do it in a small Kotlin helper (for example the `MainActivity` snippet above).

---

## Integration checklist

- [ ] `galva-sdk` and `billing-ktx` 8.0.0+ added; GitHub Packages credentials configured.
- [ ] `Galva.configure(...)` in `Application.onCreate()` with an explicit `env`.
- [ ] API key kept out of source control.
- [ ] `identify()` after sign-in **and** on every start with a restored session; `logout()` on sign-out.
- [ ] In-app messages: `autoDisplayInAppMessages = true`, or `getInAppMessage()` collected while your UI is visible with `showMessage()` called with an `Activity`.
- [ ] `gv…` scheme declared, and `handleOpenURL` called from `onCreate` and `onNewIntent`.
- [ ] If your app offers a privacy setting, it calls `setOptOut`.
- [ ] Push: `setPushToken` from `onNewToken` and on start-up, `handlePush` in `onMessageReceived`, `POST_NOTIFICATIONS` requested, notification icon meta-data set.
- [ ] Release build tested with R8 enabled.
