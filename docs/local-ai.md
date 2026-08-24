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
| LLM | `LocalLlmEngine` + `LiteRtLmBackend` (LiteRT-LM) | Yes, once a model is installed |
| Speech-to-text | `AndroidSpeechRecognizerEngine` | Depends on the installed recogniser |
| Text-to-speech | `AndroidTextToSpeechEngine` | Depends on the installed TTS app |
| Wake word | `SpeechRecognizerWakeWordEngine` | Same as above; battery-heavy |

Two of those need explaining honestly.

**The runtime ships; the weights do not.** John depends on
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), which ships its native
libraries inside its AAR — there is no NDK build, no CMake and no JNI shim in
this repository, and nothing to compile before inference works. What is still
absent is a model: a `.litertlm` file is hundreds of megabytes that belong to
the user rather than the APK. Until one is installed `LocalLlmEngine` reports
itself not ready and `CompositeLlmEngine` serves the deterministic matcher, so
John works out of the box and the model manager says plainly what is missing.

`LiteRtLmBackend` still checks for its own classes at runtime rather than
assuming them, so a build that strips the dependency degrades to the matcher
instead of dying on a missing class at the first utterance.

**The wake word uses continuous recognition.** A purpose-built keyword spotter
is the right answer and John's architecture is built for one. What ships is the
version that needs no download first, and it is honest about costing more
battery. It is off by default.

---

## Adding a language model

### 1. The runtime is already there

Nothing to build. `LiteRtLmBackend` binds LiteRT-LM
(`com.google.ai.edge.litertlm:litertlm-android`, pinned in
`gradle/libs.versions.toml`), whose AAR carries `liblitertlm_jni.so` for every
supported ABI.

It runs on `Backend.CPU()`. The manifest also declares `libvndksupport.so` and
`libOpenCL.so` as `android:required="false"` so a GPU backend can be selected
later without an install-time failure on the many phones that have neither.

Two properties of the implementation are worth knowing before changing it:

- **A fresh `Conversation` per generation.** The orchestrator sends the whole
  history it wants the model to see on every turn, so holding LiteRT-LM's KV
  cache across turns would replay that history twice.
- **Stop sequences are applied in Kotlin.** The API has no stop-sequence option,
  so `LiteRtLmBackend` truncates the completion itself. `ToolCallParser` is
  strict, and one trailing control token on otherwise valid JSON is the
  difference between an executed command and a spoken shrug.

**Other runtimes.** Implement `LlmBackend` and change one binding:

```kotlin
@Provides @Singleton
fun provideLlmBackend(backend: LlamaCppBackend): LlmBackend = backend
```

`LlamaCppBackend` is still in the tree for exactly this, unbound. It expects a
llama.cpp build with JNI bindings at
`app/src/main/jniLibs/arm64-v8a/libllama-android.so`; `System.loadLibrary` is
wrapped in `runCatching`, so a missing library is a capability that is absent
rather than a crash at some later moment. ONNX Runtime Mobile fits the same
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
| Gemma 3 1B Instruct (`.litertlm`) | ~584 MB | ~1.4 GB | **The only entry the shipped runtime can load.** Check Google's Gemma terms |
| Llama 3.2 1B Instruct Q4 | ~800 MB | ~1.2 GB | Lightest. Fast, but drops arguments on longer commands |
| Qwen 2.5 1.5B Instruct Q4 | ~1.1 GB | ~1.6 GB | Best size/quality trade for tool selection |
| Gemma 2 2B Instruct Q4 | ~1.6 GB | ~2.2 GB | Check Google's Gemma terms before shipping |
| Qwen 2.5 3B Instruct Q4 | ~2.0 GB | ~2.8 GB | Noticeably better on unusual phrasing; wants 6 GB RAM |
| Phi 3.5 Mini Instruct Q4 | ~2.3 GB | ~3.0 GB | Strong structured output; heaviest here |

The `.gguf` rows are llama.cpp formats and are listed for whoever swaps the
binding back; `LiteRtLmBackend.supportedExtensions` is `litertlm` alone. Q4_K_M
quantisation is the usual sweet spot there. `ModelDescriptor.fitsIn` requires
roughly double the model's footprint before calling a device suitable — loading
to the limit thrashes rather than runs.

`.litertlm` bundles for Gemma 3 are published by the `litert-community`
organisation on Hugging Face; `gemma3-1b-it-int4.litertlm` is the file the
catalogue entry names.

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

**Entries that target LiteRT-LM must use `ChatTemplate.PLAIN`.** This looks
wrong and is not. A `.litertlm` bundle carries its own chat template and applies
it inside `Conversation`, while `LocalLlmEngine` has already rendered the
transcript before the backend sees it. Choosing `GEMMA` here would wrap every
turn in control tokens twice — precisely the degradation described above, where
the model drifts into continuing the conversation and its JSON falls apart. So
the catalogue renders a plain transcript and hands it over as a single user
message, letting the bundle apply the real Gemma format exactly once.

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

- Inference runs on `Dispatchers.Default` and is serialised behind a `Mutex` — an
  inference context is not safe to drive from two coroutines at once, and
  concurrent generations corrupt decoder state rather than failing cleanly.
- `LlmOptions.timeoutMillis` (20 s default) bounds every generation. A stalled
  model degrades to a spoken apology, not a frozen orb.
- The model loads lazily on first use, not at startup: a cold start should not
  pay for a model the user may never invoke this session. `Engine.initialize()`
  can take ten seconds on a large bundle, which is why it never runs on the main
  thread.
- `LlmEngine.unload()` frees the weights so the OS can reclaim the memory.
- The deterministic matcher runs first, so the common commands never touch the
  model at all — which is the single biggest thing keeping battery usage sane.
