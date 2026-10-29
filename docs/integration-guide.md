# Integration guide

Everything after [installation](installation.md): identifying users, rendering in-app messages, deep links, push notifications, billing and logging.

- [Configuration reference](#configuration-reference)
- [Identify users](#identify-users)
- [User properties](#user-properties)
- [Track events](#track-events)
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

`logout()` clears the user binding and starts a fresh anonymous identity. Each call keeps the user it was made as: calls made before `logout()` that have not been uploaded yet (for example while offline) are still sent, as the signed-out user, along with the next user's, and everything recorded after `logout()` belongs to the new identity. If you registered a push token, call [`setPushToken`](#2-register-the-token) again after the next sign-in.

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
import io.galva.sdk.ProfileProperty

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

## Track events

Record what users do, like the iOS SDK's `AppEvents.track`:

```kotlin
Galva.instance.track("AddHabitButtonTapped")
Galva.instance.track("Purchase", mapOf("sku" to "pro_yearly", "price" to 9.99, "currency" to "USD"))
```

Attributes are a loose map, so you can pass one you already have. Values that are not JSON-compatible are silently dropped: strings, booleans, numbers and `null` are kept; `Date` / `Calendar` become ISO-8601 strings (UTC), `Uri` / `URL` / `UUID` / `BigDecimal` / enums become strings, and nested maps (string keys) and collections are filtered the same way. A blank event name is ignored.

For events you send often, define a typed event so call sites can't drift:

```kotlin
data class PurchaseEvent(val sku: String, val price: Double) : AppEvent {
    override val eventName: String get() = "Purchase"
    override val attributes: Map<String, Any?> get() = mapOf("sku" to sku, "price" to price)
}

Galva.instance.track(PurchaseEvent(sku = "pro_yearly", price = 9.99))
```

Events are persisted before upload, batched, retried while offline, and attributed to the user identified at the time of the call. They are dropped while the user is [opted out](#privacy-and-opt-out).

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

While opted out, `track`, `identify`, `updateProperties`, push-token registration, push interaction events, purchase reports and `session_start` are dropped, and events still waiting to be uploaded are deleted when switching to opted out. In-app messages keep working. The choice is stored on the device and survives restarts; `setOptOut(false)` turns tracking back on.

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

Observe the messages from your main / root activity and pass each one to `showMessage`. `observeInAppMessages(owner, listener)` calls the listener on the main thread while `owner` (an activity or fragment) is resumed, and stops by itself when it is destroyed. It works the same from Kotlin and Java:

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
Galva.getInstance().observeInAppMessages(this, message ->
        Galva.getInstance().showMessage(this, message));
```

An exception thrown by the listener is logged and doesn't stop later messages.

In Kotlin, the same messages are available as a `Flow` from `getInAppMessage()`, for coroutine code:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.RESUMED) {
        Galva.instance.getInAppMessage().collect { message ->
            Galva.instance.showMessage(this@MainActivity, message)
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

- **Gate it if you need to.** In the listener (or `collect`) you can skip or delay `showMessage` (for example during onboarding or checkout). Not observing at all means nothing is rendered.
- **Observe while your UI is visible.** Checking for messages does not depend on observers; a message published while nothing is observing is skipped for the rest of the app session. If your app has several top-level activities, observe in each of them (for example from a base activity) — all observers receive the same message, and `showMessage` ignores a message that is already being shown.
- **Check on demand.** Call `Galva.instance.checkForMessages()` after a user action that should trigger a workflow (for example finishing onboarding), instead of waiting for the next return to the foreground.
- The message is shown in a full-screen activity provided by the SDK; the user closes it from inside the message or with the back button.

---

## Deep links

Emails and pushes sent by Galva can link straight into a message in your app, using your app's custom scheme (`gv-…`, for example `gv-xYkIIqntk5UIIkHN`). Your exact scheme is shown in the Galva dashboard next to your API key.

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
        <!-- Your scheme from the Galva dashboard -->
        <data android:scheme="gv-xYkIIqntk5UIIkHN" />
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
gv-xYkIIqntk5UIIkHN://openCommunication?communicationId=<id>
```

All query parameters of the link are passed to the message page as `window.galvaDeepLinkParams`, so a tap carries its campaign context with it.

**Deferred until identify.** A targeted message can only be opened for a known user. If the link arrives before `identify()` (for example a cold start straight from an email), the SDK keeps the most recent link and opens it automatically right after you call `identify()`.

---

## Push notifications

Galva sends push campaigns through Firebase Cloud Messaging (FCM), or through Huawei Push Kit or Amazon Device Messaging on devices without Google Play services ([step 6](#6-devices-without-google-play-services)). You keep your own `FirebaseMessagingService`; Galva needs the device token, and it renders and tracks its own notifications.

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
            Galva.instance.handlePush(message.data)   // shows the notification and tracks it
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

Tapping a Galva notification opens your launcher activity with the push data as string extras (`communicationId`, `title`, `body`, …); with `singleTop` / `singleTask` they arrive in `onNewIntent`.

### 5. What is tracked

The SDK tracks notification interactions as regular events, with the same names as the iOS SDK. Each event carries the push data as attributes plus `id` (the communication id), and none are sent while the user is opted out.

| Event | When |
|---|---|
| `$gv_notification_presented` | The notification was actually posted. Not sent when notifications are off for the app, `POST_NOTIFICATIONS` is not granted or the Galva channel is blocked. Android only: on iOS the system shows the notification. |
| `$gv_notification_tapped` | The user tapped the notification. |
| `$gv_notification_dismissed` | The user swiped the notification away or cleared it. |

### 6. Devices without Google Play services

FCM needs Google Play services. Huawei devices without them and Amazon Fire devices get pushes from their own service instead: register that service's token and say which provider issued it. `setPushToken(token)` alone means FCM.

| Devices | Provider | Token |
|---|---|---|
| Huawei, without Google Play services | `PushProvider.HMS` | Push Kit: `HmsInstanceId.getToken(appId, "HCM")`, then `onNewToken` |
| Amazon Fire | `PushProvider.ADM` | Amazon Device Messaging registration ID, from `onRegistered` |

```kotlin
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage
import io.galva.sdk.Galva
import io.galva.sdk.PushProvider

class AppHmsMessageService : HmsMessageService() {

    override fun onNewToken(token: String) {
        Galva.instance.setPushToken(token, PushProvider.HMS)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.dataOfMap
        if (Galva.instance.isGalvaNotification(data)) {
            Galva.instance.handlePush(data)
        }
    }
}
```

`HmsInstanceId.getToken` blocks, so call it off the main thread. It returns an empty string when the token is delivered later through `onNewToken`; Galva ignores empty tokens. On ADM, pass the registration ID from `onRegistered` with `PushProvider.ADM`, and turn the message intent's extras into a `Map<String, String>` for `isGalvaNotification` and `handlePush`.

A device has one Galva push token: registering a token from another provider replaces the previous one. Sending to HMS or ADM tokens needs Galva's backend to support that provider for your app.

---

## Billing

Galva uses Google Play Billing to run purchases that start **inside** an in-app message (for example a win-back offer). You do not need to write any billing code for that — only add the `billing-ktx` dependency (see [installation](installation.md#2-add-the-dependencies)).

Purchases started inside a Galva message are acknowledged by Galva's backend, so you don't need to acknowledge them yourself. To unlock the purchased content, keep refreshing entitlements the way your app already does — Google recommends calling `BillingClient.queryPurchasesAsync()` from `onResume()`, which also picks up purchases your app did not start itself.

So Galva can attribute every subscription to the right user, the SDK reports Play purchases (order id and purchase token) with the current user's ids, once per purchase and user:

- purchases made in Galva's in-app messages, right away, whichever billing layer ran them;
- the account's newest completed purchase each time the app comes to the foreground, which covers purchases from your own paywall or another billing SDK.

To report a purchase from your own paywall right away instead of on the next app entry, call `Galva.instance.reconcileTransactions()` once your billing code has completed it (for example in your `onPurchasesUpdated`), or from a "Restore purchases" action. The RevenueCat package does this for you whenever RevenueCat reports a purchase. Nothing is sent while the user is [opted out](#privacy-and-opt-out).

### RevenueCat

Using RevenueCat? Depend on [`io.galva.sdk:galva-android-revenuecat`](https://github.com/Galva-io/galva-android-revenuecat) instead of `galva-sdk` (it brings `galva-sdk` and shares your app's RevenueCat 10.x) and pass its integration to `configure`. Purchases from Galva's messages then go through RevenueCat, and Galva follows RevenueCat's identity, so `Purchases.logIn` / `logOut` replace your `identify` / `logout` calls:

```kotlin
import io.galva.revenuecat.revenueCat
import io.galva.sdk.GalvaIntegration

Galva.configure(this, configuration, integrations = listOf(GalvaIntegration.revenueCat()))
```

Its README covers the repository, the identity options and what the integration syncs.

### Your own billing layer (purchase controllers)

If your app runs purchases through its own billing layer, install a `GalvaPurchaseController`. Purchases that messages request then run through it instead of Galva's Play Billing flow, so your layer records the purchase and unlocks the entitlement in the same session:

```kotlin
class BillingPurchaseController : GalvaPurchaseController {
    override suspend fun purchase(request: GalvaPurchaseRequest): GalvaPurchaseResult {
        // Run it yourself with request.activity, request.productId, request.basePlanId and
        // request.offerId, or use Galva's Play flow and record the result:
        val result = request.purchaseWithPlayBilling()
        if (result is GalvaPurchaseResult.Completed) MyBilling.record(result.transaction.purchaseToken)
        return result
    }
}

Galva.instance.setPurchaseController(BillingPurchaseController())
```

- `purchase` is called on the main thread, once per purchase the user starts in a message; the message waits for the result.
- Return `Completed`, `Pending` or `Cancelled`. Throw a `GalvaPurchaseException` (`ProductUnavailable`, `IneligibleForOffer`, `NetworkError`, …) for failures the message handles specifically; any other exception reaches it as a generic purchase failure.
- Attach `request.obfuscatedAccountId` to purchases you run yourself when your billing layer allows it: it's the account id Galva attaches to its own purchases.
- `setPurchaseController(null)` restores Galva's flow. The RevenueCat integration installs its own controller, so don't combine the two.

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
import io.galva.sdk.Configuration;
import io.galva.sdk.Environment;
import io.galva.sdk.Galva;
import io.galva.sdk.LogLevel;

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
Galva.getInstance().track("AddHabitButtonTapped");
Galva.getInstance().track("Purchase", Map.of("sku", "pro_yearly", "price", 9.99));
Galva.getInstance().logout();
String version = Galva.getSdkVersion();
```

In-app messages come through `observeInAppMessages`, which needs no coroutines:

```java
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Galva.getInstance().observeInAppMessages(this, message ->
                Galva.getInstance().showMessage(this, message));
    }
}
```

---

## Integration checklist

- [ ] `galva-sdk` (from Maven Central) and `billing-ktx` 8.0.0+ added.
- [ ] `Galva.configure(...)` in `Application.onCreate()` with an explicit `env`.
- [ ] API key kept out of source control.
- [ ] `identify()` after sign-in **and** on every start with a restored session; `logout()` on sign-out.
- [ ] In-app messages: `observeInAppMessages` (or `getInAppMessage()` in Kotlin) from your main activity, calling `showMessage()` with that `Activity`; or `autoDisplayInAppMessages = true`.
- [ ] `gv…` scheme declared, and `handleOpenURL` called from `onCreate` and `onNewIntent`.
- [ ] If your app offers a privacy setting, it calls `setOptOut`.
- [ ] Push: `setPushToken` from `onNewToken` and on start-up, `handlePush` in `onMessageReceived`, `POST_NOTIFICATIONS` requested, notification icon meta-data set.
- [ ] Release build tested with R8 enabled.
