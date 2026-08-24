# Setup

## Requirements

| | |
|---|---|
| JDK | 17 (the build targets JVM 17; a newer JDK is fine) |
| Android Studio | Ladybug 2024.2 or newer |
| Android SDK | 35 (compile), minimum device API 26 |
| Gradle | Wrapper included — do not install one |

## Building the app

```bash
git clone https://github.com/kingofdead6/android-assisstant.git
cd android-assisstant
./gradlew :app:assembleDebug
```

Install to a connected device:

```bash
./gradlew :app:installDebug
```

The app module needs the Android SDK and access to Google's Maven repository
(`dl.google.com`) for AGP, AndroidX, Compose, Hilt and Room.

## Building without the Android SDK

The assistant's decision pipeline is a plain Kotlin/JVM module with no Android
dependency, and it builds anywhere:

```bash
./gradlew -PskipAndroidModules=true :core:test
```

`skipAndroidModules` excludes `:app` from the build entirely, so nothing tries
to resolve the Android Gradle Plugin. This is what CI runs when it has no SDK,
and it exercises every gate in the pipeline: schema validation, tool resolution,
permission checks, the confirmation policy, tool-call parsing, and the phrase
catalogue.

> The root `build.gradle.kts` declares **no plugins**, deliberately. Declaring
> AGP there — even with `apply false` — forces Gradle to resolve it from
> Google's Maven repository during configuration of *every* build, which breaks
> this path. Each module declares the plugins it applies.

## Type-checking the Android sources without the SDK

`./gradlew :app:build` cannot run without the SDK, which would leave the Android
half of the project with no machine checking at all — and hallucinated framework
APIs only caught when someone opens Android Studio.

```bash
tools/verify-android-sources.sh
```

The script assembles a compiler out of artifacts that *are* on Maven Central:

- the Kotlin compiler (`kotlin-compiler-embeddable`),
- a real `android.jar`, borrowed from Robolectric's `android-all` artifact,
  which carries the complete framework API surface,
- small local stubs for the handful of AndroidX/Hilt annotations the verified
  sources use (`tools/verification/stubs`).

It then runs the compiler frontend over the trees listed in
`tools/verification/verified-sources.txt` and reports resolution and type
errors. Backend code generation against the Robolectric jar is expected to fail
and is ignored — only `error:` diagnostics matter.

**What it covers**: `platform/`, `tools/`, `permissions/`, `ai/`,
`integrations/` — roughly fifty files, all the framework-facing code.

**What it does not**: Compose UI, Room and Hilt code generation, and anything
needing the real AndroidX artifacts. Those are checked by a normal Gradle build.

First run downloads about 200 MB (mostly `android-all`) into `.verify-cache/`,
which is gitignored. Later runs are fast. Override the cache location:

```bash
tools/verify-android-sources.sh /path/to/cache
# or: JOHN_VERIFY_CACHE=/path/to/cache tools/verify-android-sources.sh
```

## Tests

```bash
# the decision pipeline — no SDK, no emulator, ~2s
./gradlew -PskipAndroidModules=true :core:test

# app-module logic — needs the SDK
./gradlew :app:test

# on-device — needs a connected device or emulator
./gradlew :app:connectedAndroidTest
```

`:core` ships fake engines in `testFixtures`, so the app module's tests reuse
them rather than maintaining a second set of stubs that drifts:

```kotlin
testImplementation(testFixtures(project(":core")))
```

`FakeLlmEngine`, `FakeSpeechToTextEngine`, `FakeTextToSpeechEngine`,
`FakeWakeWordEngine`, `FakeTool`, `FakePermissionGate`, `FakeEnvironment` and
`FakeMemoryStore` let the whole pipeline be tested without a model, a
microphone, or a device.

## Adding a local model

See [`local-ai.md`](local-ai.md).

## Project layout

```
core/                     plain Kotlin/JVM — the decision pipeline
  src/main/kotlin/        tool contract, LLM/speech interfaces, orchestrator
  src/testFixtures/       fake engines, shared with :app
  src/test/               116 unit tests

app/                      the Android application
  src/main/java/          platform, tools, ai, services, data, presentation
  src/test/java/          JVM tests for app-module logic
  src/androidTest/java/   instrumentation tests

tools/
  verify-android-sources.sh
  verification/           stubs and the verified source list

docs/
```

## Conventions

- Kotlin official code style (`kotlin.code.style=official`).
- Modules declare their own plugins; the root declares none.
- Dependency versions live in `gradle/libs.versions.toml`.
- New Android capabilities go through a tool. See [`tools.md`](tools.md).
