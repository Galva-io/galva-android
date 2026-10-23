# Contributing to the Galva Android SDK

Thanks for helping out. This guide covers the local setup, how the project is organized, the conventions the code follows, and how changes are tested and released.

---

## Setup

| | |
|---|---|
| JDK | 17+ to run Gradle (Android Studio's bundled JBR works) |
| Android SDK | Platform 36 |
| IDE | Android Studio (recent stable) |

1. Clone the repository and open it in Android Studio.
2. Android Studio creates `local.properties` with `sdk.dir`. For the sample app, add a **development** key to `app/local.properties` (this file is git-ignored — never commit it):

   ```properties
   GALVA_API_KEY=pk_test_xxxxxxxx
   ```

3. Build everything once:

   ```bash
   ./gradlew assembleDebug
   ```

---

## Project layout

```
:galva-sdk       public facade (io.galva.sdk.Galva) + default implementations (impl/, all internal)
:core            Configuration, Environment, ProfileProperty, operation contracts
:identity        identity model and store
:operation-queue persistent batching queue (SQLite)
:network         HTTP client and API services
:inapp-message   message activity, JS bridge, bundle cache
:billing         billing contracts, catalog and entitlement stores
:local-storage   encrypted key-value storage
:common          logger, utilities, InternalGalvaApi
:build-logic     convention plugins (Kotlin/Android setup, publishing)
:app             sample / demo app and the instrumented E2E suite
```

Apps depend only on `io.galva.sdk:galva-sdk`; the other modules are published as its dependencies.

---

## Conventions

### Public API

- **`galva-sdk` uses Kotlin explicit API mode.** Every public declaration needs a visibility modifier and an explicit type, so nothing becomes API by accident. Implementation classes under `impl/` are `internal`.
- **Other modules mark their internals with `@InternalGalvaApi`.** The modules are split for build hygiene, so their classes must be public for each other; the opt-in marker (level ERROR) keeps apps from using them. SDK modules opt in through the convention plugin. Only types that the `Galva` API exposes stay unmarked: `Configuration`, `Environment`, `ProfileProperty`, `LogLevel`, `LogSink`, `Message`, `WorkflowType`, `BillingManager` and the billing models. New top-level declarations in those modules should be `internal`, `private`, or `@InternalGalvaApi` unless they are meant for apps.
- Add `@JvmOverloads` / `@JvmStatic` where a public Kotlin API has default arguments or lives in a companion, so Java callers get a usable API.
- A change to the public API needs a docs update (`Readme.md` and `docs/`).

### Behaviour

- Public `Galva` calls must return immediately: enqueue work on the SDK's channel / scope, never block the caller's thread.
- `Galva.configure` keeps the main thread to recording the configuration and registering lifecycle observers; build new components in `setUp` (background), not in `configure`. `MainThreadPerformanceTest` fails if it grows past its budget.
- Never crash the host app: validate input, log, and drop instead of throwing from public calls.
- Keep parity with the iOS SDK ([galva-ios](https://github.com/Galva-io/galva-ios)) unless a platform difference requires otherwise; mention deliberate differences in the PR.
- Use structured coroutines: the SDK scope passed into components, `viewModelScope` / `lifecycleScope` in activities — no ad-hoc `CoroutineScope(...)`.
- Hold `applicationContext`, never an `Activity`, in anything that outlives a screen.

### Logging

- Log through the SDK `Logger` only — no `println`, `android.util.Log` or `printStackTrace` in library code.
- Use the lazy form (`logger.debug { ... }`) and pick the level carefully: `ERROR`/`WARN` are visible by default; request / response payloads, emails and tokens only at `VERBOSE`. Never log the API key.

### Style

- Match the surrounding code: naming, formatting and comment density. Comments explain *why*, not *what*.
- Keep changes focused; unrelated refactors go into their own PR.

---

## Testing

```bash
# Unit tests (JVM + Robolectric) for all modules
./gradlew testDebugUnitTest

# Lint
./gradlew lint

# Instrumented end-to-end suite against a mock backend (needs a device or emulator)
./gradlew :app:connectedDebugAndroidTest
```

- Every bug fix comes with a test that fails without the fix.
- Queue, storage, bridge and selection logic are covered by unit tests in their module; extend them rather than adding ad-hoc checks.
- The E2E suite in `app/src/androidTest` runs the SDK against MockWebServer; add a scenario there for user-visible flows (identify, in-app messages, deep links, opt-out).
- Main-thread budget tests in `galva-sdk` guard the "public calls return immediately" contract; keep them green.

Pull requests run lint and unit tests on CI (`.github/workflows/pull-request-checks.yml`).

---

## Pull requests

1. Branch from `main`.
2. Make the change with tests and docs; run unit tests and lint locally.
3. Open a PR describing what changed, why, and any behaviour difference from the iOS SDK.
4. Never commit secrets (`local.properties`, keys, tokens). If one is committed by mistake, rotate it immediately.

---

## Releasing

1. Set `VERSION_NAME` in `gradle.properties` (semantic versioning; `-SNAPSHOT` for pre-releases).
2. Merge to `main`. The `Publish SDK to GitHub Packages` workflow builds, signs and publishes all modules to `https://maven.pkg.github.com/Galva-io/galva-android`.
3. Update the version shown in the docs if the install instructions changed.

To try a build locally before releasing, publish to Maven Local with `./gradlew publishToMavenLocal` (see [docs/installation.md](docs/installation.md#using-a-local-build-of-the-sdk)).

---

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
