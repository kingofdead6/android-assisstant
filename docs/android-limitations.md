# Android limitations

Where John cannot do what a user might reasonably expect, this file records
**what the restriction is**, **which API it comes from**, and **what John does
instead**. Nothing here is missing work; it is the boundary of what Android
allows an ordinary third-party app.

The rule the codebase follows: when something is not possible, say so out loud
rather than silently doing nothing.

---

## Messaging: John cannot send a WhatsApp message

| | |
|---|---|
| **Feature** | Send a message through WhatsApp, Telegram, Messenger, Signal |
| **API** | None. No public API exists for any of them |
| **Permission** | n/a |
| **Versions** | All |
| **What John does** | Opens the conversation with the text pre-filled via the app's deep link (`https://wa.me/<number>?text=…`) or an `ACTION_SEND` intent, and says *"it's ready — tap send"* |

Android provides no general mechanism for one app to operate another, and none
of these apps publishes a send API. The only technical route to an actual
automated send is driving the target app's UI through an accessibility service,
which breaks on every redesign and is not something an assistant should do
behind a user's back.

SMS is different: `SmsManager.sendTextMessage` with `SEND_SMS` really does send.
John still confirms first, because a sent message cannot be recalled.

Implementation: [`MessagingManager`](../app/src/main/java/com/john/assistant/platform/MessagingManager.kt).
`MessageOutcome.Composed` and `MessageOutcome.Sent` are separate types so a
caller cannot accidentally report one as the other.

---

## Wi-Fi cannot be switched on

| | |
|---|---|
| **API** | `WifiManager.setWifiEnabled` |
| **Versions** | Returns `false` for third-party apps from **Android 10 (API 29)** |
| **What John does** | Opens `Settings.Panel.ACTION_WIFI` — a bottom sheet the user can act on without leaving John |

---

## Bluetooth cannot be switched on

| | |
|---|---|
| **API** | `BluetoothAdapter.enable()` |
| **Versions** | Deprecated in **Android 13 (API 33)**; a no-op without privileged permissions |
| **What John does** | Fires `BluetoothAdapter.ACTION_REQUEST_ENABLE`, the system's own confirm dialog |

Reading Bluetooth state works and needs `BLUETOOTH_CONNECT` from Android 12. When
that is missing, John reports *"I can't check Bluetooth without permission"* —
which is not the same as "Bluetooth is off", and the code keeps the distinction
(`isBluetoothOn()` returns `Boolean?`).

---

## Media control needs notification access to be good

| | |
|---|---|
| **API** | `MediaSessionManager.getActiveSessions(ComponentName)` |
| **Permission** | An enabled `NotificationListenerService` — there is no lesser grant |
| **Versions** | All |
| **What John does** | Uses MediaSession when granted; otherwise `AudioManager.dispatchMediaKeyEvent`, the same signal a headset button sends |

Without notification access, play/pause/next still work with every player, but
John cannot name what is playing or target a specific app. So the permission
upgrades media control from *works* to *works and can tell you what's playing*.

---

## The app list is hidden

| | |
|---|---|
| **API** | `PackageManager.queryIntentActivities` |
| **Versions** | Restricted by package visibility from **Android 11 (API 30)** |
| **What John does** | Declares a `<queries>` block for the launcher intent and the specific actions it dispatches |

`QUERY_ALL_PACKAGES` is deliberately **not** used: it is restricted on Google
Play and John does not need it. The narrow declaration is enough to resolve
"open YouTube" against what is actually installed.

Apps without a launcher activity cannot be opened at all. John reports that
rather than failing silently.

---

## Exact reminders need a permission

| | |
|---|---|
| **API** | `AlarmManager.setExactAndAllowWhileIdle` |
| **Permission** | `SCHEDULE_EXACT_ALARM` (Android 12) / `USE_EXACT_ALARM` (Android 13+) |
| **What John does** | Uses the exact API when available; otherwise falls back to `setAndAllowWhileIdle` **and says the reminder may be a few minutes late** |

Alarms themselves are handed to the clock app via `AlarmClock.ACTION_SET_ALARM`,
which needs no permission at all and means the alarm survives John being
uninstalled.

`EXTRA_SKIP_UI` asks the clock app not to open its own screen — clock apps are
allowed to ignore it, so John promises only that the alarm was set.

---

## Default assistant

| | |
|---|---|
| **API** | `Settings.ACTION_VOICE_INPUT_SETTINGS`, `RoleManager.ROLE_ASSISTANT` |
| **Versions** | The assistant role is not requestable by ordinary apps |
| **What John does** | Declares an `ACTION_ASSIST` intent filter, which puts it in Android's assistant picker, and offers to open that picker |

What this gets you and what it does not:

- ✅ John appears in **Settings → Apps → Default apps → Digital assistant app**.
- ✅ Launching by that route opens John already listening.
- ❌ It does **not** grant the long-press-home or squeeze gestures Google
  Assistant gets, which vary by OEM and are often not available to third parties
  at all.
- ❌ It does **not** replace Bixby on Samsung devices. Samsung's assistant
  bindings are not open to third-party apps.

Fully replacing the system assistant requires a `VoiceInteractionService` with
`BIND_VOICE_INTERACTION`, a bundled recognition service and a session service —
and even then, several OEMs restrict which app may hold the role.

---

## Background listening

| | |
|---|---|
| **API** | Foreground service, `FOREGROUND_SERVICE_TYPE_MICROPHONE` |
| **Versions** | Service type required from **Android 10**; declared at `startForeground` and enforced from **Android 14** |
| **What John does** | Runs a foreground service with a permanent, visible notification |

There is no other way to hold a microphone in the background, and Android is
strict about the terms. John meets all of them and does not try to hide the
notification: something listening to a room should be visible in the shade the
whole time it is doing so.

**Battery.** The bundled wake word restarts the platform recogniser in a loop,
which is much heavier than a dedicated keyword spotter. It is off by default,
and both the wake word and background operation are separate switches. See
[`docs/local-ai.md`](local-ai.md) for plugging in a real keyword spotter.

---

## Speech recognition is not guaranteed to be on-device

| | |
|---|---|
| **API** | `SpeechRecognizer`, `RecognizerIntent.EXTRA_PREFER_OFFLINE` |
| **Versions** | `isOnDeviceRecognitionAvailable` exists from **Android 13 (API 33)** |
| **What John does** | Sets `EXTRA_PREFER_OFFLINE` and reports what can actually be determined |

Whether audio leaves the phone depends on which recogniser the user has
installed. `AndroidSpeechRecognizerEngine.runsLocally` returns the honest answer
rather than a promise, and the privacy screen shows it. A fully offline path
(on-device Whisper) plugs in behind `SpeechToTextEngine`.

The platform recogniser also **cannot transcribe a buffer** — it owns the
microphone itself. `transcribe()` returns a clear failure rather than pretending.

---

## Text-to-speech may use a network voice

The platform engine routes to whichever TTS app the user has selected, and some
use network voices. `AndroidTextToSpeechEngine.runsLocally` is therefore `false`,
and the privacy screen says "depends on your TTS engine". A local neural engine
(Piper, an ONNX voice) plugs in behind `TextToSpeechEngine`.

---

## Galaxy Buds have no special API

Samsung publishes no public SDK for them. They appear to Android as an ordinary
A2DP/HFP device — which is exactly what makes them work.

John therefore does **not** try to force a route. When earbuds are connected the
platform already sends media and speech to them; fighting that would break the
case it was meant to fix. The one genuine choice is the *input* route while
listening, and `AudioRouter.preferHeadsetMic()` handles both API eras
(`setCommunicationDevice` from Android 12, the SCO link before it).

Anything claiming Buds-specific control is inventing it.

---

## Accessibility automation is inherently fragile

| | |
|---|---|
| **API** | `AccessibilityService`, `AccessibilityNodeInfo` |
| **Permission** | Enabled by the user in Android's accessibility settings; no API to request it |
| **What John does** | Offers it as optional, uses it only when nothing else fits, and reports failure plainly |

UI automation depends on another app's layout, which changes without warning.
John's accessibility service subscribes to almost nothing — it reads the window
when a tool asks and not otherwise — so it is not watching the screen, and it
stores nothing.

Every screen tool can return a failure, and John says so rather than retrying
blindly or claiming success.

---

## Taking a photo

John opens the camera app (`INTENT_ACTION_STILL_IMAGE_CAMERA`) and the user
presses the shutter. Capturing silently would need the CAMERA permission and a
capture session — an assistant that can take a photo without anyone noticing is
a different and much worse application.
