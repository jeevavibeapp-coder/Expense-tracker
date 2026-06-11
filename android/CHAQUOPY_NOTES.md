# Chaquopy integration notes

Embeds a pure-Python Flask server (`spendwise.android_entry.start_server`) inside
the APK via the [Chaquopy](https://chaquo.com/chaquopy/) Gradle plugin so the app
runs fully offline.

## Version decision

| Component            | Value     | Source / reason |
|----------------------|-----------|-----------------|
| Android Gradle Plugin| **8.9.1** | Unchanged — existing `android/build.gradle`. |
| Gradle wrapper       | 8.11.1    | Unchanged — `gradle/wrapper/gradle-wrapper.properties`. |
| **Chaquopy**         | **16.1.0**| First release to support AGP **8.9 – 8.13**. |
| buildPython (CI)     | 3.11      | Chaquopy 16.1.0 requires buildPython >= 3.8. |

### Why Chaquopy 16.1.0 (and not 17.0.0)

From the official Chaquopy changelog / version summary:

- **16.1.0** (2025-05-07): "Android Gradle plugin versions **8.9 to 8.13** are now
  supported." Inherits Python 3.8–3.13 and **min API 24** from 16.0.0, and the
  buildPython >= 3.8 rule. This directly covers our AGP **8.9.1** and our
  `minSdkVersion = 24`.
- **17.0.0** (2025-12-01): added AGP **9.0 – 9.2** (cumulative 7.3–9.2, so it also
  covers 8.9). However it bumps the supported Python range to 3.10–3.14 **and
  changes the buildPython rule** to "must have the same Python major and minor
  versions as the app." That tighter coupling is more fragile in CI for no benefit
  here, since 16.1.0 already supports our exact AGP.

**Conclusion: pin Chaquopy 16.1.0. No AGP change was required** — AGP stays at
8.9.1, which 16.1.0 supports natively. The Chaquopy 16.1.0 Gradle plugin artifact
(`com.chaquo.python:gradle:16.1.0`) is published on **Maven Central**, which is
already declared in both `buildscript.repositories` and `allprojects.repositories`,
so no additional Maven repository was added.

### AGP / Chaquopy support matrix (from official `versions.rst`)

| Chaquopy | Python      | Supported AGP | Min API |
|----------|-------------|---------------|---------|
| 17.0     | 3.10 – 3.14 | 7.3 – 9.2     | 24      |
| **16.1** | 3.8 – 3.13  | **7.0 – 8.13**| 24      |
| 16.0     | 3.8 – 3.13  | 7.0 – 8.8     | 24      |
| 15.0     | 3.8 – 3.12  | 7.0 – 8.5     | —       |

## Gradle wiring (Groovy / legacy `apply plugin` syntax)

This project uses the legacy `apply plugin: '...'` style, so Chaquopy is wired the
same way:

- `android/build.gradle` — `buildscript.dependencies`:
  `classpath 'com.chaquo.python:gradle:16.1.0'`
- `android/app/build.gradle`:
  - `apply plugin: 'com.chaquo.python'` (AFTER `com.android.application`)
  - `defaultConfig { ndk { abiFilters "armeabi-v7a","arm64-v8a","x86","x86_64" } }`
  - `defaultConfig { python { buildPython "python3"; pip { install "Flask" } } }`
  - `sourceSets { main { python.srcDir "../../python_app" } }`
    (relative to `android/app/` -> `/home/.../Expense-tracker/python_app`, which
    contains package `spendwise`, making `spendwise.android_entry` importable.)

In Chaquopy 16.x the configuration lives in `android.defaultConfig.python { ... }`
and `android.sourceSets.main.python` (NOT the top-level `chaquopy { }` block that
17.x's docs use with the modern `plugins {}` DSL).

## Runtime flow (MainActivity.java)

1. `super.onCreate` loads bundled `dist` assets (brief splash).
2. `Python.start(new AndroidPlatform(this))` if not already started.
3. Background thread: `getModule("spendwise.android_entry").callAttr("start_server",
   getFilesDir().getAbsolutePath())`.
4. Poll `http://127.0.0.1:8765` (HttpURLConnection, ~15s timeout) until it answers.
5. `runOnUiThread` -> `getBridge().getWebView().loadUrl("http://127.0.0.1:8765")`.

`android:usesCleartextTraffic="true"` (AndroidManifest.xml) permits the localhost
HTTP connection.

## CI

`.github/workflows/android.yml` gains an `actions/setup-python@v5` (3.11) step
before the Gradle build so Chaquopy's buildPython can run pip. `npx cap sync
android` and `./gradlew assembleDebug assembleRelease` are unchanged.

## Risks / uncertainties

- Chaquopy downloads its Python runtime + builds the requirements ("Flask") on the
  first CI build; this adds time but is standard. Flask and its deps are pure
  Python, so no NDK toolchain / native wheels are needed.
- Only `mavenCentral()` + `google()` are declared; 16.1.0 is on Maven Central, so
  the classpath resolves without `https://chaquo.com/maven`.
- Could not build locally (no Android SDK in this environment); validation happens
  in GitHub CI.
