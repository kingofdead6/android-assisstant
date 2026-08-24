# Security

John is a privileged application. It can place calls, send messages, read
notifications and — if the user allows it — read the screen. The design
assumption throughout is that **the model may be wrong, confused, or
manipulated**, and that nothing about the user's safety may depend on it
behaving.

## The model chooses; the application decides

```
LLM output  →  registry lookup  →  schema validation  →  offline check
            →  permission check →  confirmation policy →  execute
```

Every stage is enforced in code, in `AssistantOrchestrator`. The system prompt
asks the model to behave; this pipeline is what makes it irrelevant whether it
does.

## What the model cannot do

**Emit code.** The model produces a tool name and a JSON argument object. There
is no `eval`, no shell, no reflection-by-name, no filesystem path it controls.
Adding one would be the only way to break this property.

**Invent a tool.** `ToolRegistry.resolve` is an exact lookup after
normalisation. There is **no fuzzy matching**: `open_apps` does not resolve to
`open_app`, and `delete_everything` resolves to nothing. Executing a near-match
would mean a hallucination silently becoming an action.

**Exceed a tool's schema.** Undeclared arguments are dropped before `execute`
is reached, and declared ones are type-checked, bounds-checked and enum-checked.
A tool cannot receive an argument it did not ask for.

**Skip a confirmation.** The policy is read from settings by the orchestrator.
It is not in the prompt, not in the tool's arguments, and not something the
model can address. `HIGH`-risk tools always confirm — even when a user has put
them in `never_confirm`.

**Grant itself a permission.** `PermissionGate` is checked before `execute`, and
again by tools that can discover a missing capability mid-flight.

**Reach a tool the user turned off.** Disabling a tool removes it from both the
registry and the schema shown to the model, so it stops being suggested as well
as stopping being runnable.

These are asserted in `AssistantOrchestratorTest` — hallucinated names, invalid
arguments, missing permissions, unclear confirmations, and a `HIGH`-risk tool
with a `never_confirm` entry all have tests.

## Consent

**Silence is never consent.** `AffirmationDetector` returns `YES`, `NO` or
`UNCLEAR`, and only `YES` proceeds. A mis-transcription, background speech or a
half-heard word re-asks the question.

**Negation wins on mixed input.** "yeah, don't do that" classifies as `NO`.

**Confirmation is deterministic.** Answering a yes/no question does not spend an
LLM turn. A small model having a bad day can never turn "no, don't" into a sent
message, and the round-trip is instant.

## Data

**What is stored**

| | Where | Lifetime |
|---|---|---|
| Conversation history | Room | User-set retention, default 30 days, enforced on every turn |
| Memory | Room | Until deleted; only what the user asked John to remember |
| Settings | DataStore | Until reset |
| Account tokens | `EncryptedSharedPreferences` | Until disconnected |

**What is never stored**

- Audio. Nothing is written to disk at any point.
- Notification contents. Held in memory by `NotificationAccess`, cleared when
  access is revoked.
- Screen content. The accessibility service reads on demand and keeps nothing.
- Contacts. Every lookup goes to the provider and is discarded.

**Backup is off.** `allowBackup=false`, and `data_extraction_rules.xml` excludes
everything from both cloud backup and device-to-device transfer. A backup of
this app is a copy of someone's assistant transcript; it should not exist
anywhere they did not put it.

**Debug logs are dropped in release builds.** They carry utterances and tool
arguments, which should not sit in a shipped device's log buffer.
`AndroidLogger` gates them on `BuildConfig.DEBUG`.

## Prompt content

Two guards on what reaches the model:

- **Referents are an allow-list.** Tool results can contain notification bodies
  and phone numbers; only keys like `app_label` and `track` carry forward into
  the next prompt. `ConversationContextManagerTest` asserts a verification code
  in a notification does not.
- **Memory is withheld when disabled.** `RoomMemoryStore` enforces the setting
  itself, so no code path can write or read a memory behind it.

## Credentials

**No secret is shipped.** GitHub sign-in uses the OAuth **device flow**
specifically because it needs no client secret — a secret in an APK is not a
secret, it is extractable by anyone who downloads the app. The client ID is a
public identifier and is entered by the user, not baked in.

**Tokens live in the Keystore.** `EncryptedSharedPreferences`, hardware-backed
where the device supports it. If encrypted storage cannot be created,
`SecureTokenStore` **refuses to fall back to plaintext**: connected accounts are
disabled and the UI says why.

**Scopes are read-only.** `repo:status read:user notifications`. John cannot open
an issue, push, or comment. A voice assistant acting on a misheard command in
someone's repository is a problem it should not be able to have.

**A revoked token is cleared**, not retried forever, so the user gets a
"connect it again" message instead of silent failures.

## Accessibility

The most dangerous capability John can hold, and the one handled most narrowly:

- **Off unless the user enables it** in Android's own settings. There is no API
  to request it, and John does not try to route around that.
- **Pull, not push.** `onAccessibilityEvent` is deliberately empty. John reads
  the window when a tool asks and not otherwise — there is no background stream
  of screen contents.
- **No gestures.** `canPerformGestures="false"`. John can tap a labelled
  element; it cannot draw arbitrary gestures on the screen.
- **Bounded traversal.** Node walks have a hard budget, so a hostile or
  pathological view hierarchy cannot stall the assistant.
- **Failure is reported.** Every screen tool can return a failure, and John says
  so rather than retrying blindly.

## Reporting

Security issues should be reported privately to the repository owner rather than
as a public issue.
