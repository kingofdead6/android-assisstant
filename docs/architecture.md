# Architecture

## The one idea

The language model chooses; the application decides.

Everything else follows from that. The model never emits code, shell, or Android
calls — it emits a tool name and arguments, as JSON. The application looks the
name up in a registry, validates the arguments against a declared schema, checks
permissions, applies a confirmation policy, and only then runs anything.

That boundary is not advice given to the model in a prompt. It is enforced in
code, in [`AssistantOrchestrator`](../core/src/main/kotlin/com/john/assistant/core/assistant/AssistantOrchestrator.kt),
and it holds whether the model cooperates or not.

## Modules

```
:core   plain Kotlin/JVM — what John should do
:app    Android — how it gets done
```

`:core` has **no dependency on the Android SDK**. It holds the tool contract and
registry, the LLM/STT/TTS/wake-word interfaces, tool-call parsing, conversation
context, the confirmation policy, prompt assembly and the orchestrator.

This is not tidiness for its own sake. It buys three things:

1. **The decision pipeline is unit-testable on any JVM** — no emulator, no
   Robolectric, no SDK. `./gradlew -PskipAndroidModules=true :core:test` runs
   116 tests covering every gate in about two seconds.
2. **The dependency only points one way.** Android provides tools; it cannot be
   depended on *by* the reasoning. Nothing in `:core` can reach for a Context,
   so nothing can accidentally couple a decision to a device detail.
3. **Engines are swappable by construction.** Every AI component is an interface
   in `:core` with its implementation in `:app`, wired in one Hilt module.

## Package layout

```
core/
  tool/          AssistantTool, ToolRegistry, ToolResult, ToolParameters, RiskLevel, PermissionKey
  llm/           LlmEngine, ChatMessage, ToolCallParser, ChatTemplate, CompositeLlmEngine
    rules/       the deterministic phrase matcher
  speech/        SpeechToTextEngine, TextToSpeechEngine, WakeWordEngine
  conversation/  ConversationContextManager, PendingAction, AffirmationDetector
  policy/        ConfirmationPolicy
  prompt/        SystemPrompts, PromptBuilder
  memory/        MemoryStore, MemoryEntry
  assistant/     AssistantOrchestrator, AssistantEvent, PermissionGate, DeviceEnvironment
  util/          AssistantLogger, TimeSource, TimeExpressionParser

app/
  platform/      thin wrappers over the framework — apps, media, contacts, calls,
                 messaging, calendar, alarms, notifications, audio, accessibility
  tools/         AssistantTool implementations, one package per domain
  ai/            llm/ stt/ tts/ wakeword/ model/ — engine implementations
  permissions/   PermissionManager, PermissionCatalogue, PermissionState
  services/      foreground service, notification listener, accessibility service, receivers
  data/          Room database, DataStore settings, repositories
  session/       AssistantSession — sequences speech, orchestration and persistence
  presentation/  Compose UI: home, history, settings, permissions, models
  integrations/  optional connected accounts (GitHub)
  di/            Hilt modules
```

## A turn, end to end

```
                      wake word (local)
                             │
                    AssistantSession
                             │  opens the microphone
                    SpeechToTextEngine
                             │  transcript
                    AssistantOrchestrator
                             │
        ┌────────────────────┴────────────────────┐
        │ pending question?                        │
        │   yes → AffirmationDetector / ChoiceMatcher
        │   no  → PromptBuilder → LlmEngine        │
        └────────────────────┬────────────────────┘
                             │ tool name + arguments
                     ToolRegistry.resolve      ← unknown name = does not exist
                             │
                 ToolParameters.validate       ← undeclared arguments dropped
                             │
                    offline check              ← online-only tools fail early
                             │
                     PermissionGate            ← missing capability stops here
                             │
                  ConfirmationPolicy           ← HIGH always asks
                             │
                    AssistantTool.execute
                             │ ToolResult
                    AssistantEvent.Reply
                             │
                    TextToSpeechEngine
```

`AssistantOrchestrator.handle()` returns a `Flow<AssistantEvent>`, so the UI
renders state transitions as they happen rather than reconstructing them from a
return value. The flow always ends with `Done`.

Turns are serialised behind a mutex. John does one thing at a time — which is
also what makes "yes" unambiguous.

## Why the LLM is not on the critical path

`CompositeLlmEngine` tries a deterministic phrase matcher first and only falls
through to the model when it does not match:

- "pause", "what's my battery", "set an alarm for 7 am" resolve in microseconds
  with no inference and no battery cost;
- anything with real language in it — *"tell Mom I'll be twenty minutes late
  because the bus broke down"* — goes to the model, which is what it is for;
- if the model is missing, still loading, or errors, the deterministic answer is
  still there. John degrades; it does not break.

A model error never erases a usable deterministic result. That is asserted in
`CompositeLlmEngineTest`.

## Conversation context

Two separate stores, deliberately:

- **Short-term** (`ConversationContextManager`) — a rolling window of recent
  turns plus the referents the last tool produced. This is what makes "open
  YouTube" → "search for AI tutorials" work.
- **Long-term** (`MemoryStore`, Room) — only what the user explicitly asked John
  to remember.

Keeping them apart means clearing history never silently wipes preferences, and
disabling memory never breaks follow-up questions.

Referents carried forward are an **allow-list**, not a filter. Tool results can
contain notification bodies and phone numbers; none of that reaches the next
prompt. `ConversationContextManagerTest` asserts it.

## Threading

- Tools are `suspend` and called off the main thread.
- `SpeechRecognizer` is pinned to `Dispatchers.Main` — it is a bound-service
  client with a main-looper callback contract, and getting this wrong produces a
  recogniser that silently never calls back.
- Inference runs on `Dispatchers.Default` and is serialised: llama.cpp contexts
  are not thread-safe, and two concurrent generations corrupt the KV cache
  rather than failing cleanly.
- `TextToSpeechEngine.speak` genuinely suspends until the utterance ends, so the
  microphone never reopens while John is still talking.

## Adding a capability

1. Write an `AssistantTool` in `app/tools/<domain>/`.
2. Add it to the constructor list in `di/ToolModule.kt`.

That list *is* the capability boundary: a tool not constructed there does not
exist as far as the model is concerned, so a hallucinated call to it cannot
execute. Adding a capability is a deliberate, reviewable line of code — which is
what you want from the file that decides what an assistant may do with someone's
phone.

See [`docs/tools.md`](tools.md) for the contract.
