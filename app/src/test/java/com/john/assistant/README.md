# App-module tests

These run on the JVM with `./gradlew :app:test`, which needs the Android SDK and
access to Google's Maven repository.

They deliberately cover the app-module logic that is *pure* — permission
mapping by API level, app-name normalisation, model sizing, tool contracts —
because that is the part where a mistake is invisible until it reaches a
particular Android version or a particular phone.

The assistant's decision pipeline is not tested here. It lives in `:core` and is
covered by that module's suite, which runs on any JVM with
`./gradlew -PskipAndroidModules=true :core:test` — no SDK, no emulator.

Framework-facing sources are additionally type-checked against a real
`android.jar` by `tools/verify-android-sources.sh`. See `docs/setup.md`.
