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
- **No inference runtime is bundled.** Choosing and shipping a native `.so` is
  the app builder's decision; `docs/local-ai.md` explains how.

---

## Getting started

### 1. Clone and open

```bash
git clone https://github.com/kingofdead6/android-assisstant.git
cd android-assisstant
```

Open the folder in **Android Studio Ladybug (2024.2)** or newer and let it sync.
You need **JDK 17** and **Android SDK 35**; Android Studio will offer to install
the SDK if it is missing.

### 2. Build and install

```bash
./gradlew :app:installDebug
```

Or press Run in Android Studio with a device attached. Minimum supported version
is **Android 8.0 (API 26)**.

### 3. Grant permissions as you go

John asks for nothing on first launch. Each permission is requested the first
time a feature needs it, and **Settings → Permissions** explains what each one
unlocks and how Android grants it. Notification access and accessibility have no
permission dialog at all — Android only lets you enable them from its own
settings screens, and the dashboard says so instead of showing a button that
does nothing.

### 4. Configure a local model (optional)

**Settings → AI → Models** lists models that run on a phone, with download size,
RAM requirement and licence shown *before* anything downloads. Nothing downloads
on its own.

Running a model also needs a native inference runtime, which this repository
does not bundle. See `docs/local-ai.md`.

### 5. Enable the wake word (optional)

**Settings → Voice → Wake word**. Add **Keep listening in the background** to
have it work when John is not on screen; that runs a visible, permanent
notification, because something listening to a room should be visible while it
does so.

### 6. Make John your assistant (optional)

**Settings → Automation → Make John your assistant** opens Android's assistant
picker. What a third-party assistant is allowed to replace varies by Android
version and manufacturer — see `docs/android-limitations.md`.

---

## Building without the Android SDK

The assistant's decision pipeline lives in `:core`, a plain Kotlin/JVM module
with no Android dependency. It builds and tests anywhere:

```bash
./gradlew -PskipAndroidModules=true :core:test
```

The framework-facing Android sources can also be type-checked without the SDK,
against a real `android.jar`:

```bash
tools/verify-android-sources.sh
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
| [`docs/setup.md`](docs/setup.md) | Development environment, builds, tests, verification |

## Licence

No licence has been chosen for this repository yet — add one before publishing
or accepting contributions.

Models are licensed separately by their publishers. The model manager shows each
licence before anything downloads, and the catalogue notes where terms need
checking (Gemma and Llama both carry their own).
