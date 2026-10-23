# Troubleshooting

Start every investigation with debug logging on and Logcat filtered to the SDK:

```kotlin
Galva.instance.setLogLevel(LogLevel.DEBUG)
```

```bash
adb logcat -s Galva
```

For problems resolving or compiling the dependency, see [Installation problems](installation.md#installation-problems).

---

## `IllegalStateException: Galva not configured`

A Galva API that needs the configured SDK (`currentUserId`, `getInAppMessage()`, `billingManager`, …) was used before `Galva.configure(...)`.

- Configure from `Application.onCreate()`, not from an activity.
- Make sure the `Application` subclass is registered with `android:name` in the manifest.
- Code that can run before `onCreate` finishes (content providers, `androidx.startup` initializers) must not call Galva.

## Nothing is sent to the backend

- Has the user opted out? `Galva.instance.isOptedOut` returns `true` while tracking is off.

- Check that `env` matches the API key (development key ↔ `Environment.Development`, production key ↔ `Environment.Production`). A mismatch shows up as HTTP `401` / `403` in the logs.
- Uploads are batched: by default profile updates are sent when 10 are pending or after about 10 seconds, whichever comes first (the backend can lengthen both). The queue pauses while the app is in the background and resumes on the next foreground.
- Offline? Updates stay on disk and are sent when connectivity returns.

## In-app message does not show

1. Is `getInAppMessage()` being collected while the activity is resumed? Add a log line inside `collect`.
2. Is `showMessage()` receiving an **`Activity`** (not the application context)?
3. Does the backend have a pending in-app message for this user? Look for the `identities/communications` request and its response in the logs.
4. For targeted messages, has `identify()` been called with the expected user id?
5. Look for `Failed to resolve message` / `Failed to resolve bundle` errors — the message content or its web bundle could not be downloaded (network, wrong `env`).
6. Only one message is published per return to the foreground, and none while a message is on screen. Send the app to the background and back, or call `Galva.instance.checkForMessages()`.
7. A message is published once per app session. If it was published while nothing was collecting (for example the app came to the foreground on a screen that does not collect), restart the app to see it again.

## In-app message opens but is blank or buttons do nothing

- Release builds with R8: add the rules from [R8 / ProGuard](installation.md#r8--proguard).
- Check Logcat for WebView / JavaScript errors.

## Purchase inside a message does not complete

- The product, base plan and offer referenced by the message must exist and be **active** in Play Console.
- The device must be signed in with a Google account that can make purchases; for testing, add it to **License testing** in Play Console.
- The build must be installed from a Play testing track (internal testing is enough) or be signed with the same key as the uploaded build.
- Play Billing must resolve to **8.0.0+** (`dependencyInsight`, see [installation](installation.md#2-add-the-dependencies)).

## Purchase completed in a message, but the content is still locked

Galva's backend acknowledges purchases made inside a message, but your app decides what to unlock. Purchases made through the SDK's billing connection are not delivered to your app's `PurchasesUpdatedListener`, so refresh entitlements with `BillingClient.queryPurchasesAsync()` when the app resumes (or after the message closes).

## Deep link does not open the message

- The scheme in the intent filter must match the one in the Galva dashboard exactly and start with `gv`.
- `handleOpenURL` must be called from **both** `onCreate` and `onNewIntent`; with `launchMode="singleTop"` (or `singleTask`) warm links arrive in `onNewIntent`.
- The link is held until the user is identified — call `identify()` (including on app start for restored sessions).
- Test from the command line:

  ```bash
  adb shell am start -W -a android.intent.action.VIEW \
    -d "gvYOURSCHEME://openCommunication?communicationId=<id>" your.package.name
  ```

## Push notifications do not appear

- Android 13+: has the user granted `POST_NOTIFICATIONS`? Galva skips posting without it.
- Is the token registered? `setPushToken` must be called from `onNewToken` **and** with the current token on start-up.
- Is `handlePush(message.data)` called from `onMessageReceived` for Galva messages (`isGalvaNotification(message.data)` returns `true`)?
- Has the user disabled the **"Galva SDK Notifications"** channel in system settings?
- Notification shows a white square: set the `io.galva.sdk.default_notification_icon` meta-data to a monochrome drawable (see [Push notifications](integration-guide.md#4-icon-and-color-recommended)).

## Collecting a support report

Please include:

- the Galva SDK version (`io.galva.sdk:galva-sdk:<version>`),
- device model and Android version,
- the Play Billing version your app resolves,
- a Logcat capture at `LogLevel.DEBUG` covering the problem (remove API keys, emails and other personal data before sharing).
