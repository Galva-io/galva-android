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
- **The public API lives in `galva-sdk` alone.** It depends on the other modules with `implementation`: they are published for the app's runtime but are never on its compile classpath, so no type of theirs may appear in a public signature. Types apps use (`Configuration`, `Environment`, `ProfileProperty`, `LogLevel`, `LogSink`, `Message`, `WorkflowType`, …) are declared in `io.galva.sdk` and mapped to the internal ones inside the SDK.
- **Other modules mark their internals with `@InternalGalvaApi`.** The modules are split for build hygiene, so their classes must be public for each other; the opt-in marker (level ERROR) flags any use outside the SDK. SDK modules opt in through the convention plugin.
- **`galva-sdk/api/galva-sdk.api` records the public API.** `./gradlew :galva-sdk:apiCheck` (part of `check`) fails when the compiled API no longer matches it. After a deliberate API change, run `./gradlew :galva-sdk:apiDump` and commit the updated file with the change. `PublicApiTest` fails if a type from another module, or from kotlinx.serialization, shows up in it.
- **Integration packages build on `@GalvaIntegrationApi`** (opt-in, level ERROR), the counterpart of iOS's `@_spi(GalvaIntegration)`: the `GalvaIntegration` constructor, `IntegrationContext` and the hand-built `GalvaPurchaseRequest`. Apps only use the factories integration packages provide (e.g. `GalvaIntegration.revenueCat()` from [galva-android-revenuecat](https://github.com/Galva-io/galva-android-revenuecat)). Integrations must stick to the public API plus this opt-in, never `@InternalGalvaApi`.
- Add `@JvmOverloads` / `@JvmStatic` where a public Kotlin API has default arguments or lives in a companion, so Java callers get a usable API.
- A change to the public API needs a docs update (`Readme.md` and `docs/`).

### Behaviour

- Public `Galva` calls must return immediately: enqueue work on the SDK's channel / scope, never block the caller's thread.
- `Galva.configure` keeps the main thread to recording the configuration and registering lifecycle observers; build new components in `setUp` (background), not in `configure`. `MainThreadPerformanceTest` fails if it grows past its budget.
- Never crash the host app: validate input, log, and drop instead of throwing from public calls. In practice:
  - no `error()` / `check()` / `!!` on SDK state: components are null until `setUp` finishes, and code reads them with `?: return`;
  - SDK coroutines run in `galvaScope`, whose `CoroutineExceptionHandler` logs anything that escapes; the call queue catches per call;
  - code the platform calls back (JavaScript bridge, activities, receivers, `whenReady` blocks) catches its own failures and answers with an error;
  - a missing or old Play Billing Library degrades to `UnavailableBillingManager` instead of failing setup.
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

# Public API matches galva-sdk/api/galva-sdk.api (after a deliberate change: :galva-sdk:apiDump)
./gradlew :galva-sdk:apiCheck

# Instrumented end-to-end suite against a mock backend (needs a device or emulator)
./gradlew :app:connectedDebugAndroidTest

# Performance suite: the demo app with vs without Galva; writes app/build/reports/perf-report.md
./scripts/perf.sh
```

The performance suite mirrors the iOS SDK's. `SdkFootprintTest` starts the demo app five times in fresh processes **with** Galva (`:perf_sdk`, against the mock backend) and five times **without** it (`:perf_baseline`), and compares medians. It fails when Galva adds more than **8 MB** of memory (PSS) at idle; launch-to-first-frame and CPU deltas are reported only. The probe lives in `app/src/perf`. Emulators are noisy, so re-run before treating one failure as a regression.

To find **where** startup CPU goes, profile release-like code:

```bash
./scripts/profile.sh            # or: --runs 3 --modes sdk
```

It builds the demo's `profiling` build type (R8-shrunk, not debuggable, `profileable`, names kept with `-dontobfuscate -dontoptimize`), starts the probe in fresh processes with and without Galva, records an ART sampling method trace of each, and prints thread CPU charged to the deepest SDK frame and the leaf method that spent it. Traces stay in `app/build/reports/profile` for Android Studio. Debug builds overstate SDK CPU about tenfold; judge CPU on this build, not on the perf suite's debug numbers.

- Every bug fix comes with a test that fails without the fix.
- Queue, storage, bridge and selection logic are covered by unit tests in their module; extend them rather than adding ad-hoc checks.
- The E2E suite in `app/src/androidTest` runs the SDK against MockWebServer; add a scenario there for user-visible flows (identify, in-app messages, deep links, opt-out).
- Main-thread budget tests in `galva-sdk` guard the "public calls return immediately" contract; keep them green.
- `CrashSafetyTest` calls every public API on an unconfigured SDK; extend it when adding public API.

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
