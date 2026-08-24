# Permissions

John asks for **nothing** on first launch. Every permission is requested the
first time a feature actually needs it, and the dashboard at
**Settings → Permissions** explains what each one unlocks.

Android grants permissions three different ways, and they behave nothing alike.
Modelling that difference is what
[`PermissionCatalogue`](../app/src/main/java/com/john/assistant/permissions/Permission.kt)
exists for — a dashboard that offers an "Allow" button for accessibility would
be offering a button that does nothing.

| Kind | How it is granted | Examples |
|---|---|---|
| **Runtime** | Standard system dialog | Microphone, Contacts, Phone |
| **Special access** | A settings screen the user must navigate to; there is *no API to request it* | Notification access, Accessibility, Exact alarms |
| **Implicit** | Nothing to grant on this API level | Internet; Bluetooth before Android 12 |

---

## What John asks for, and why

### Microphone — `RECORD_AUDIO`

**Why** To hear the wake word and your commands.
**Without it** John cannot listen at all. Typed input still works.
**When asked** The first time you tap the microphone or enable the wake word.

Audio captured while waiting for the wake word is never written to disk. Whether
transcription happens on-device depends on your installed recogniser — see
[`android-limitations.md`](android-limitations.md#speech-recognition-is-not-guaranteed-to-be-on-device).

### Notifications — `POST_NOTIFICATIONS` *(Android 13+)*

**Why** To show the listening notification and deliver reminders.
**Without it** Background listening cannot run, and reminders fire silently.
**When asked** When you enable background listening or set your first reminder.

### Notification access — special access

**Why** To read and summarise your notifications, and to name what is playing.
**Without it** "Read my notifications" does not work, and media control still
works but John cannot say what is playing.
**How** Android has no dialog for this. John opens the notification-access list;
you find John and switch it on.

This is the broadest permission John asks for — it can see every message and
code on the phone. What John does with it:

- notifications are held **in memory only**, never written to the database or a
  log line;
- the cache is cleared the moment access is revoked;
- the default answer to "read my notifications" is a **count per app**, not the
  contents. Bodies are only read when you ask for a specific app.

### Phone — `CALL_PHONE`

**Why** To place calls.
**Without it** John offers to open the dialler with the number filled in.
**When asked** The first time you ask John to call someone.

### Contacts — `READ_CONTACTS`

**Why** To work out who you mean when you say a name.
**Without it** You can still call a number you dictate.
**When asked** The first time you name a contact.

Lookups are never cached. Every one goes to the provider and the result is
discarded once the call is placed.

### SMS — `SEND_SMS`

**Why** To send a text without you having to press send.
**Without it** John opens your messaging app with the message ready — which is
what it does for every other messaging app anyway.
**When asked** The first time you ask John to text someone.

### Calendar — `READ_CALENDAR`, `WRITE_CALENDAR`

**Why** Read to answer "what's on my calendar tomorrow". Write only if you turn
off the confirmation step.
**Without it** John opens the calendar app's editor pre-filled, which needs no
permission at all — and is the default.
**When asked** The first time you ask about your calendar.

### Camera — `CAMERA`

**Why** Only for the flashlight. Opening the camera app needs no permission, and
John never captures anything itself.

### Bluetooth — `BLUETOOTH_CONNECT` *(Android 12+)*

**Why** To see what is connected, such as your earbuds.
**Without it** Audio still routes to your earbuds — Android does that. John just
cannot name the device.

### Alarms & reminders — `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM`

**Why** So a reminder arrives when you asked rather than whenever Doze next lets
the device wake.
**Without it** Reminders still fire, but may be minutes late — and John says so
when it schedules one.

### Accessibility — special access

**Why** Optional. Lets John read the screen and tap for you in apps that offer
no other way to be controlled.
**Without it** Everything except the three screen tools works normally.
**How** Android's accessibility settings. There is no dialog.

Off by default, and narrow by design: John's service subscribes to almost
nothing and reads the window only when a tool asks. It is not watching your
screen, and it stores nothing. See
[`JohnAccessibilityService`](../app/src/main/java/com/john/assistant/services/JohnAccessibilityService.kt).

### Internet — `INTENT`, `ACCESS_NETWORK_STATE`

**Why** Web search, maps and connected accounts. The assistant itself does not
need it.
**Note** Tools that need a connection declare `worksOffline = false`, and John
says *"that isn't available because you're offline"* rather than failing
mysteriously.

---

## What John never asks for

- `QUERY_ALL_PACKAGES` — restricted on Google Play, and unnecessary: a narrow
  `<queries>` block resolves app names.
- Location — no tool needs it. Maps searches are handed to the maps app, which
  asks for its own.
- Storage — models live in John's private files directory.
- `READ_SMS` — John can send a text; it has no reason to read your messages.

---

## The three ways a permission can be missing

The dashboard distinguishes them, because the fix is different in each case:

| State | What it means | Button |
|---|---|---|
| **Not granted** | Never asked, or refused once | Allow → system dialog |
| **Blocked** | Refused twice; Android will not show the dialog again | Open app settings |
| **Off** | Special access, no dialog exists | Open Android settings |

Showing "Allow" for the second and third would produce a button that silently
does nothing — the most confusing possible outcome, and the one this modelling
exists to avoid. `PermissionStateTest` asserts the mapping.
