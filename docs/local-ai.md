# Local AI

John is designed so that every AI component is an interface in `:core` with a
swappable implementation in `:app`. Replacing one is a single binding change in
`di/AiModule.kt`; nothing above the interface knows or cares.

```kotlin
interface LlmEngine          // pick a tool, or answer in words
interface SpeechToTextEngine // microphone → text
interface TextToSpeechEngine // text → speech
interface WakeWordEngine     // "Hey John"
```

## What ships, and what it costs

| Component | Shipped implementation | Runs on-device? |
|---|---|---|
| Intent | `RuleBasedLlmEngine` — deterministic phrase matcher | Yes, always |
| LLM | `LocalLlmEngine` + a swappable `LlmBackend` | Yes, once a runtime is added |
| Speech-to-text | `AndroidSpeechRecognizerEngine` | Depends on the installed recogniser |
| Text-to-speech | `AndroidTextToSpeechEngine` | Depends on the installed TTS app |
| Wake word | `SpeechRecognizerWakeWordEngine` | Same as above; battery-heavy |

Two of those need explaining honestly.

**No inference runtime is bundled.** Shipping a prebuilt native `.so` would add
tens of megabytes of code nobody reading this repository could audit, and the
choice of runtime belongs to whoever builds the app. So `LlamaCppBackend`
reports `isSupported = false` when its library is absent, `LocalLlmEngine`
reports itself not ready, and `CompositeLlmEngine` serves the deterministic
matcher instead. John works — the model manager just says no runtime is
installed.

**The wake word uses continuous recognition.** A purpose-built keyword spotter
is the right answer and John's architecture is built for one. What ships is the
version that needs no download first, and it is honest about costing more
battery. It is off by default.

---

## Adding a language model

### 1. Provide a native runtime

Build [llama.cpp](https://github.com/ggerganov/llama.cpp) for Android with its
JNI bindings and place the result at:

```
app/src/main/jniLibs/arm64-v8a/libllama-android.so
```

`LlamaCppBackend` expects three JNI entry points:

```kotlin
private external fun nativeLoadModel(path: String, contextTokens: Int): Long
private external fun nativeGenerate(
    handle: Long, prompt: String, maxTokens: Int,
    temperature: Float, topP: Float, stopSequences: Array<String>,
): String?
private external fun nativeFreeModel(handle: Long)
```

`System.loadLibrary` is wrapped in a `runCatching`, so a missing library is a
capability that is absent — not a crash at some later moment.

**Other runtimes.** Implement `LlmBackend` and change one binding:

```kotlin
@Provides @Singleton
fun provideLlmBackend(backend: MediaPipeLlmBackend): LlmBackend = backend
```

MediaPipe LLM Inference, ONNX Runtime Mobile and LiteRT all fit the same
four-method interface. `arm64-v8a` is the only ABI worth targeting for
inference; 32-bit devices do not have the memory.

### 2. Install a model

**Settings → AI → Models**. Each entry shows download size, RAM requirement,
licence and a warning when the device cannot run it — *before* anything starts.
Nothing downloads on its own.

The catalogue ships with **empty download URLs on purpose**: model repositories
move, and a stale hardcoded link that 404s halfway through a gigabyte is worse
than asking for the address once. Paste a URL, or copy a file you already have.

Downloads land at `.part` and are renamed only on completion, so an interrupted
download can never be loaded as corrupt weights.

Models live in the app's private files directory: uninstalling John removes
them, and nothing else can read them.

### 3. Choose the right size

| Model | Download | RAM | Notes |
|---|---|---|---|
| Llama 3.2 1B Instruct Q4 | ~800 MB | ~1.2 GB | Lightest. Fast, but drops arguments on longer commands |
| Qwen 2.5 1.5B Instruct Q4 | ~1.1 GB | ~1.6 GB | Best size/quality trade for tool selection |
| Gemma 2 2B Instruct Q4 | ~1.6 GB | ~2.2 GB | Check Google's Gemma terms before shipping |
| Qwen 2.5 3B Instruct Q4 | ~2.0 GB | ~2.8 GB | Noticeably better on unusual phrasing; wants 6 GB RAM |
| Phi 3.5 Mini Instruct Q4 | ~2.3 GB | ~3.0 GB | Strong structured output; heaviest here |

Q4_K_M quantisation is the usual sweet spot. `ModelDescriptor.fitsIn` requires
roughly double the model's footprint before calling a device suitable — loading
to the limit thrashes rather than runs.

### 4. Match the prompt template

This is not cosmetic. An instruction-tuned model given the wrong control tokens
degrades badly: it drifts into continuing the conversation instead of answering,
and its JSON formatting falls apart — which for John means every command becomes
a shrug.

`ChatTemplate` covers ChatML (Qwen), Llama 3, Gemma, Phi and a plain fallback,
and the template is part of the model's catalogue entry rather than a global
setting. `ChatTemplateTest` pins each format.

Gemma has no system role, so the system prompt is folded into the first user
turn. Tool observations are presented as user turns in every template: no small
model handles a distinct tool role reliably.

---

## Adding on-device speech recognition

`AndroidSpeechRecognizerEngine` is the default because it exists everywhere and
streams partial results. It is not unconditionally local — see
[`android-limitations.md`](android-limitations.md#speech-recognition-is-not-guaranteed-to-be-on-device).

For a fully offline path, implement `SpeechToTextEngine` over
[whisper.cpp](https://github.com/ggerganov/whisper.cpp):

```kotlin
interface SpeechToTextEngine {
    val runsLocally: Boolean
    fun listen(languageTag: String): Flow<ListeningEvent>
    suspend fun transcribe(audio: AudioBuffer, languageTag: String): TranscriptionResult
    fun cancel()
}
```

`AudioBuffer` is 16 kHz mono 16-bit PCM — the common denominator every on-device
recogniser expects, which is why it is the contract rather than one engine's
detail.

Note the split: `listen()` is the live-microphone path, `transcribe()` works on
a captured buffer. Whisper needs the second because it operates on complete
utterances; the platform recogniser only implements the first and returns a
clear failure for the other rather than pretending.

`ggml-tiny.en` (75 MB) and `ggml-base.en` (142 MB) are both in the catalogue.
Base is clearly better on accented speech.

Then set `runsLocally = true` — honestly — and bind it:

```kotlin
@Provides @Singleton
fun provideSpeechToText(engine: WhisperSpeechToTextEngine): SpeechToTextEngine = engine
```

---

## Adding a neural voice

Implement `TextToSpeechEngine` over [Piper](https://github.com/rhasspy/piper) or
an ONNX voice model.

One requirement that is easy to miss: **`speak` must genuinely suspend until the
utterance finishes.** Without that, the assistant reopens the microphone while
it is still talking and transcribes its own voice. `AndroidTextToSpeechEngine`
does this with an utterance-progress listener; whatever you implement must do
the equivalent.

Route audio with `AudioRouter.speechAttributes()` — `USAGE_ASSISTANT` is what
makes music duck rather than John talking over it, and what sends the voice to
connected earbuds along with everything else.

---

## Adding a real wake-word engine

This is the highest-value replacement in the whole file. A dedicated keyword
spotter is a few hundred kilobytes of weights and a fraction of a percent of
battery, against continuous speech recognition.

```kotlin
interface WakeWordEngine {
    val phrase: String
    val isAvailable: Boolean
    fun isRunning(): Boolean
    fun start()
    fun stop()
    fun detections(): Flow<WakeWordDetection>
    var sensitivity: Float
}
```

Options:

- **Porcupine** (Picovoice) — a custom "Hey John" keyword can be trained on their
  console. Commercial licence for production.
- **openWakeWord** — open source, ONNX; train your own phrase.
- **A custom TFLite model** — a small CNN over mel spectrograms is enough for a
  single phrase.

Two hard requirements, whatever you choose:

1. **Detection runs entirely on the device.** Audio captured while waiting for
   the wake word never leaves the phone and is never written to disk. That is
   the property the microphone-permission rationale promises the user.
2. **Release the microphone when firing.** The session takes it next; an engine
   that keeps holding it will fight the recogniser for the same hardware. The
   shipped engine calls `stop()` from `fire()` for exactly this reason.

Then bind it:

```kotlin
@Provides @Singleton
fun provideWakeWord(engine: PorcupineWakeWordEngine): WakeWordEngine = engine
```

---

## Performance notes

- Inference runs on `Dispatchers.Default` and is serialised — llama.cpp contexts
  are not thread-safe, and two concurrent generations corrupt the KV cache
  rather than failing cleanly.
- `LlmOptions.timeoutMillis` (20 s default) bounds every generation. A stalled
  model degrades to a spoken apology, not a frozen orb.
- The model loads lazily on first use, not at startup: a cold start should not
  pay for a model the user may never invoke this session.
- `LlmEngine.unload()` frees the weights so the OS can reclaim the memory.
- The deterministic matcher runs first, so the common commands never touch the
  model at all — which is the single biggest thing keeping battery usage sane.
