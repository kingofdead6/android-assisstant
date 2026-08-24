# John

A voice-controlled, local-first AI assistant for Android.

```
"Hey John, play some music."
"Hey John, call Mom."
"Hey John, send Mom a WhatsApp message saying I'll be home at eight."
```

John is not a chatbot with a microphone attached. The language model's job is to
pick **one tool** from a fixed set and fill in its arguments; the application
decides whether that tool may run. Everything the model produces passes through
schema validation, a permission check and a confirmation policy before anything
touches the device.

```
voice → wake word → speech-to-text → context → LLM
                                                 ↓
                                         tool + arguments
                                                 ↓
                              validate → permission → confirm → execute
                                                 ↓
                                        result → speech
```

---

## Contents

- [What works today](#what-works-today)
- [Where John is honest rather than impressive](#where-john-is-honest-rather-than-impressive)
- [Requirements](#requirements)
- [Install it](#install-it)
- [First run](#first-run)
- [Choosing how John thinks](#choosing-how-john-thinks)
- [Optional setup](#optional-setup)
- [Troubleshooting](#troubleshooting)
- [Building without the Android SDK](#building-without-the-android-sdk)
- [Documentation](#documentation)

---

## What works today

| | |
|---|---|
| **Apps** | Open any installed app by name, resolved from what is actually installed. Asks which one when several match. |
| **Web** | Search, open a URL, find a place on the map. |
| **Media** | Play, pause, skip, previous, volume, and "what's playing" via MediaSession. |
| **Phone** | Call a contact by name, with disambiguation when they have several numbers. |
| **Messaging** | SMS directly; WhatsApp, Telegram, Messenger and Signal pre-filled and ready to send. See the honesty note below. |
| **Notifications** | Summarise by app, or read one app's notifications aloud. |
| **System** | Battery, time, date, connectivity, Bluetooth, flashlight, camera. |
| **Time** | Alarms and timers via the clock app; John's own exact reminders. |
| **Calendar** | Read today and tomorrow; create events. |
| **Memory** | "Remember that my music app is Spotify" — and John uses it next time. |
| **GitHub** | Optional. Repositories and notification summaries, read-only. |
| **Screen** | Optional accessibility automation for apps with no other way in. |

John works **with no model installed**. A deterministic phrase matcher handles
the common commands instantly and with no inference cost; a language model adds
the ability to understand phrasing the matcher does not cover.

### Where John is honest rather than impressive

These are real Android limits, not missing work. `docs/android-limitations.md`
has the full list with API references.

- **No app can send a WhatsApp, Telegram or Messenger message on your behalf.**
  None of them publishes an API for it. John opens the conversation with the
  message typed and says *"it's ready — tap send"*, rather than claiming a send.
- **Wi-Fi and Bluetooth cannot be switched on by a third-party app** (since
  Android 10 and 13 respectively). "Turn on Bluetooth" opens the system's own
  toggle.
- **John cannot make itself your default assistant.** It can appear in Android's
  assistant picker; the rest is the user's choice and the OEM's policy.
- **The bundled wake word uses continuous speech recognition**, which costs
  noticeably more battery than a dedicated keyword spotter. It is off by
  default, and the architecture is ready for Porcupine or openWakeWord.
- **The inference runtime ships; the weights do not.** John bundles LiteRT-LM,
  which carries its own native libraries, so there is nothing to compile. No
  model file is included — a 584 MB download belongs to the user, not the APK.
  Until one is installed, the deterministic matcher answers everything.
- **Speech timeouts are hints, not guarantees.** John asks the recogniser to
  allow a two-second pause before ending your sentence, but Android lets a
  recogniser ignore that. Some OEM recognisers do. See
  [Troubleshooting](#the-microphone-cuts-me-off-mid-sentence).

---

## Requirements

### To run John

- **Android 8.0 (API 26)** or newer. Below this there is no
  `NotificationListenerService` rebind, no foreground-service type enforcement
  and no `CameraManager.setTorchMode` — it would be a different app.
- **~2 GB free storage** if you want an on-device model (584 MB for the file,
  plus headroom John reserves before it will start a download).
- **~3 GB RAM** for the smallest bundled-catalogue model to be comfortable. John
  shows the requirement per model and warns when your phone is short.

### To build John

| | |
|---|---|
| **JDK** | 17 or newer (tested on 17 and 23) |
| **Android SDK** | API 35 (`compileSdk`/`targetSdk`); `minSdk` is 26 |
| **Android Studio** | Ladybug (2024.2) or newer — optional, the CLI is enough |
| **Gradle** | 8.14.3, supplied by the wrapper — do not install it yourself |

Everything else (AGP 8.13.2, Kotlin 2.3.20, Hilt 2.58) is pinned in
`gradle/libs.versions.toml` and fetched on first build.

---

## Install it

### Option A — build from source (recommended)

```bash
git clone https://github.com/kingofdead6/android-assisstant.git
cd android-assisstant
```

Point the build at your Android SDK. Create `local.properties` in the repo root:

```properties
# macOS / Linux
sdk.dir=/home/you/Android/Sdk

# Windows — forward slashes, even here
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

> Android Studio writes this file for you the first time you open the project.
> You only need to create it by hand for a pure command-line build.

Connect a device with **USB debugging** enabled, confirm it is visible, and
install:

```bash
adb devices          # your device should be listed as "device", not "unauthorized"
./gradlew :app:installDebug
```

On Windows without a POSIX shell, use `gradlew.bat :app:installDebug`.

### Option B — build an APK to copy across

```bash
./gradlew :app:assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the
phone and open it. You will need to allow **install from unknown sources** for
whichever app you use to open it.

### First build is slow

Expect **3–6 minutes** while Gradle downloads AGP, Kotlin, Hilt and Compose.
Later builds are incremental and take seconds. If it fails, check
[Troubleshooting](#troubleshooting) before retrying — a re-run rarely fixes a
configuration problem.

---

## First run

**John asks for nothing on launch.** No onboarding wizard, no permission wall,
no account. You get the orb and a microphone button.

Each permission is requested the first time a feature actually needs it.
**Settings → Permissions** explains what each one unlocks and how Android grants
it. Notification access and accessibility have no permission dialog at all —
Android only lets you enable those from its own settings screens, and the
dashboard says so rather than showing a button that does nothing.

Try it with no setup at all:

```
"What's my battery?"
"Open Spotify."
"What time is it?"
```

Those run through the deterministic matcher — no model, no network, no delay.

---

## Choosing how John thinks

**Settings → AI models** is where you pick which engine answers. There are two,
and the card showing **"In use"** is the one currently answering.

### Option 1 — on-device model (private, free, offline)

The LiteRT-LM runtime is already inside the APK, so a `.litertlm` model file is
the only missing piece. The catalogue ships **no download URLs on purpose** —
model repositories move, and a dead link mid-download is worse than asking once.

Two ways to install a model:

1. **Paste a download URL** into the model's row and tap *Download*.
2. **Import a file you already have** — tap *Import file*, pick the `.litertlm`
   from storage. This streams the file in chunks, so a 584 MB model is copied
   without ever being held in RAM.

Then tap **"Use this one"** on that row. The status under the orb should change
to name your model.

Recommended starting point: **Gemma 3 1B Instruct (LiteRT-LM)** — 584 MB, wants
about 1.4 GB of RAM. Check Google's Gemma terms before shipping anything built
on it.

### Option 2 — Hugging Face Inference API (no download)

Useful on a phone without the RAM for a local model.

1. Get a token at [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens)
   (a free read token is enough).
2. Paste it into **API token** — this is **required**, not optional. The
   Inference API rejects anonymous requests.
3. Enter a **Model ID** such as `HuggingFaceH4/zephyr-7b-beta`.
4. Tap **Use Hugging Face model**.

The button stays disabled until both fields are filled, because a
half-configured backend can only fail at the next thing you say. Your token is
stored in `EncryptedSharedPreferences`, keyed from the Android Keystore — never
in plain preferences, the database, a log line, or source.

**Prompts leave your phone with this option.** The card says so while it is
active.

### Switching back

Tap **"Answer with this model"** on any installed local model. If Hugging Face
is selected but not properly configured, John falls back to an installed local
model and logs why rather than failing at you.

---

## Optional setup

### Wake word

**Settings → Voice → Wake word.** Add **Keep listening in the background** to
have it work when John is not on screen; that runs a visible, permanent
notification, because something listening to a room should be visible while it
does so.

### Make John your assistant

**Settings → Automation → Make John your assistant** opens Android's assistant
picker. What a third-party assistant may replace varies by Android version and
manufacturer — see `docs/android-limitations.md`.

### GitHub

**Settings → Integrations.** Read-only: repositories and notification
summaries. The token lives in the same encrypted store as the Hugging Face one.

---

## Troubleshooting

### The microphone cuts me off mid-sentence

John asks the recogniser for a two-second silence allowance
(`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` and friends). Android
documents these as **hints** — a recogniser may ignore them, and some OEM
recognisers do, cutting off after roughly half a second.

If it still clips you:

- Set **Google** as the speech recognition service in Android settings, rather
  than the OEM default.
- Tune the constants at the bottom of
  `app/src/main/java/com/john/assistant/ai/stt/AndroidSpeechRecognizerEngine.kt`
  (`SILENCE_BEFORE_END_MILLIS`, `MINIMUM_UTTERANCE_MILLIS`) and rebuild.

### "Failed to punch uncompressed elf file", or the model won't load

The LiteRT-LM native library must be packaged uncompressed. The build already
enforces this:

```kotlin
androidResources { noCompress += listOf("litertlm", "so") }
packaging { jniLibs { useLegacyPackaging = true } }
```

Verify your APK actually honoured it:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep litertlm_jni
```

The compressed and uncompressed sizes should be identical. If they differ, do a
clean build (`./gradlew clean :app:assembleDebug`).

### The home screen says "no model" with a model installed

Check logcat — the load failure is reported with its real reason:

```bash
adb logcat -s LiteRtLm:* LocalLlm:* ConfiguredLlm:*
```

A `ClassNotFoundException` means the LiteRT-LM AAR was shrunk out of your build;
an `UnsatisfiedLinkError` means the `.so` is missing for your ABI or was
packaged compressed.

### Hugging Face requests fail

The error names the fix. `401` is the token, `403` means the model's licence
needs accepting on the Hugging Face website, `404` is the model ID, `429` is
rate limiting, `503` means the model is still warming up — try again shortly.

### Build fails: `SDK location not found`

`local.properties` is missing or points somewhere wrong. See
[Install it](#option-a--build-from-source-recommended). On Windows use forward
slashes.

### Build fails: `Unsupported class file major version`

Your JDK is older than 17. Check with `java -version`, then point Gradle at a
newer one:

```bash
./gradlew -Dorg.gradle.java.home=/path/to/jdk17 :app:assembleDebug
```

---

## Building without the Android SDK

The assistant's decision pipeline lives in `:core`, a plain Kotlin/JVM module
with no Android dependency. It builds anywhere:

```bash
./gradlew -PskipAndroidModules=true :core:build
```

The framework-facing Android sources can also be type-checked without the SDK,
against a real `android.jar`:

```bash
tools/verify-android-sources.sh
```

Build the application with the SDK present:

```bash
./gradlew :app:assembleDebug
```

`docs/setup.md` explains what each covers.

---

## Documentation

| | |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | Module layout, the turn pipeline, why the core is Android-free |
| [`docs/tools.md`](docs/tools.md) | The tool contract, every tool, and how to add one |
| [`docs/security.md`](docs/security.md) | What the model is and is not allowed to do |
| [`docs/android-permissions.md`](docs/android-permissions.md) | Every permission, why John asks, what breaks without it |
| [`docs/android-limitations.md`](docs/android-limitations.md) | What Android does not allow, and what John does instead |
| [`docs/local-ai.md`](docs/local-ai.md) | Plugging in an LLM, STT, TTS or wake-word engine |
| [`docs/setup.md`](docs/setup.md) | Development environment, builds, verification |
| [`web/`](web/) | The project website (React + Tailwind + Vite) — how the pipeline works, in one page |

## Licence

No licence has been chosen for this repository yet — add one before publishing
or accepting contributions.

Models are licensed separately by their publishers. The model manager shows each
licence before anything downloads, and the catalogue notes where terms need
checking (Gemma and Llama both carry their own).
