# Fusion Voice Text Post Processor + Android Local LLM — feasibility preflight

Decision-oriented preflight, not an implementation. No product source was modified, no PR was
created, no CI was triggered, no model was downloaded, no benchmark was run.

---

## 1. Exact Android source HEAD

- Repository: `/home/choicky/coding-projects/fcitx5-android`
- Baseline: latest `origin/fusion` after `v0.1.3-fusion.9`
- Inspected HEAD: `11a7b4f0857354cefd8bcdeb52be64ccb47c0fc7`
- Read-only research worktree: `/tmp/fcitx5-android/llm-postprocessor-preflight/repo` (detached);
  the normal fusion checkout was not touched.
- The `v0.1.3-fusion.9` annotated tag points at this same commit (release task result), so the
  inspected HEAD is the shipped production baseline.

## 2. Authority consulted

Current authority in `/home/choicky/coding-projects/fcitx5-fusion`:

- `AGENTS.md` (authority order, CLOSED EVIDENCE RULE, 最小修改边界 "不引入平行状态机或过度抽象")
  and `/home/choicky/coding-projects/fcitx5-android/AGENTS.md` (workspace lifecycle, diff review).
- `docs/DECISIONS.md` **D017 — ASR 与 LLM 后处理解耦 (Accepted)**: "ASR 只负责 Audio → Raw
  Transcript。LLM/Text Post Processor 独立、可关闭，ASR Provider 与 LLM Provider 分别配置。"
- `docs/REQUIREMENTS.md` §10 (the exact Audio → ASR → Raw Transcript → Optional Text Post
  Processor → Final Transcript chain; "LLM 必须可以完全关闭"), §11 (data-flow transparency:
  "是否继续发送给 LLM" and "LLM Provider/endpoint" must be knowable; "启用 ASR 不等于把
  transcript 自动发送给 LLM"), §12 (Voice minimal-modification boundary).
- `docs/ROADMAP.md` Phase 6 "Optional LLM Post-processing" = **FUTURE**, and
  `docs/first-release-baseline.md` records the Text Post Processor as NOT IMPLEMENTED / DEFERRED.

Verdict: the post-processor is an accepted but deliberately deferred capability. Nothing in
current source or current authority contradicts §1, so there is **no architecture-level conflict
to reopen**. Historical evidence used only where current source is silent:
`docs/asr-productization-worklog.md:86` and `docs/asr-productization-acceptance.md` §2.14/4.2
(see items 12 and 19).

## 3. Current ASR → transcript → IME call chains (Fusion.9, executable code)

All production providers already terminate in one event: `VoiceBackend.Events.onFinal(token, text?)`
(`app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceBackend.kt`), consumed only by
`VoiceInputFlow` (the sole implementer handed to `backend.start(...)` at `VoiceInputFlow.kt:111`).

| Path | Terminal code | Where Raw Transcript becomes final | Where provider ownership ends |
|---|---|---|---|
| System ASR | `SystemAsrBackend.kt:75` `onResults` → `release()` → `events.onFinal(token, transcript)` | inside the `RecognitionListener` | before the callback (`recognizer.destroy()`) |
| Local ASR, all three production models (`XAsrOffline`, `XAsrStreaming960`, `FunAsrNano`, `LocalAsr.kt:32,45,56` `production = true`) | `LocalAsrBackend.kt:228` `post { events.onFinal(token, text) }` after `loader.join()` + `session.finish()` | on the IO coroutine, marshalled to main | recognizer lease returned in `finally` (`LocalAsrBackend.kt:112`), session released |
| Doubao (product BYOK `Doubao` and debug PoC `DoubaoAsr`) | `DoubaoAsrBackend.kt:247` `finish(Outcome.Final)` → `release(graceful = true)` → `events.onFinal` | main-thread `post{}` | WebSocket closed before the callback |
| Qwen / Tencent / Self-hosted (SherpaOnnx, FunAsr2Pass, OpenAiCompatible, FunAsrNano) | `NetworkAsrBackend.kt` listener `onFinal` → `release()` → `events.onFinal(token, text)` | main-thread `post{}` | client `cancel()` before the callback |
| Capture probe (debug only, never text) | `CaptureProbeBackend.kt:93` `events.onFinal(token, null)` | — | — |
| External Android Voice Input (Model A / D055) | none — `VoiceInputComponent.startExternalVoiceInput()` switches IME | n/a | **never enters this pipeline by design** |

Convergence and delivery (`VoiceInputFlow.kt:181-191`):

```
onFinal(token, text)
  → session.complete(token)        // token ownership resolved exactly once
  → disarm()                       // watchdog off
  → releaseBackend()               // backend.cancel(); backend = null
  → blank ? output.clearComposing() : output.commit(text)
  → notifyState()                  // Idle
```

`VoiceInputComponent` implements `Output.commit` as `voiceAudit.record(CommittedEvent)` +
`service.commitText(text)` → `FcitxInputMethodService.commitText` (`:427`) →
`currentInputConnection.commitText`, replacing any composing text. Partial recognition travels a
different way: `onPartial` → `output.updateComposing` → `updateVoiceComposingText` (`:460`,
underlined composing).

Distinguished categories, as required: partial/intermediate recognition (preedit only, or logged
only), final Raw Transcript (the `onFinal` payload), final user-visible transcript (what
`output.commit` hands to the editor), error (`onError` → `reportError`, no transcript),
cancellation (`cancel()`/`close()`/`onStartInput`, no transcript), fallback (D035 `tryFallback`,
transcript not yet produced).

## 4. Recommended common post-processing seam

**One seam, one place: `VoiceInputFlow.onFinal`, between `releaseBackend()` and `output.commit(text)`.**

```
ASR providers ──(VoiceBackend.Events.onFinal = common Raw Transcript event)──►
VoiceInputFlow.onFinal:
    session.complete(token) ; disarm() ; releaseBackend()
    ──► Optional TextPostProcessor (async; ownership per item 5)
    output.commit(processed ?: raw)
    notifyState()
──► existing final delivery → FcitxInputMethodService.commitText → editor
```

Why this is the smallest correct point:

- It is the only place where the ASR-side token decision is already resolved (`session.complete`)
  before text is delivered, so exactly one owner takes responsibility for the pending result
  instead of each provider making its own decision. It does **not** by itself settle ownership of
  an *asynchronous* processor result delivered after that call; that window is item 5.
- It is provider-independent by construction: all six production provider families already pass
  through it (`VoiceInputFlow` is backend-agnostic; see `VoiceInputFlow.kt:8-17` doc and the
  table above). No per-provider `ASR A → LLM`, `ASR B → LLM`, `ASR C → LLM` wiring is needed,
  and source proves none is warranted.
- `releaseBackend()` already runs before the commit, so mic/network ownership for the ASR stage
  is fully closed before the processor starts: D017's "post-processing operates on text after
  ASR" holds structurally, not just by convention.
- `VoiceInputFlow` receives no vendor or framework type; it only needs one collaborator, so no
  `VoiceBackend`/`AsrServiceId`/`VoiceBackendKind` type changes are implied. The processor also does
  not need a parallel session machine; the one lifecycle question it opens — ownership of a result
  that arrives after `session.complete()` — is scoped in item 5.
- `text.isNullOrBlank()` keeps the existing behaviour for a blank transcript: nothing is
  processed and nothing is committed.

Out of scope by construction: the External Android Voice Input provider (D055) never produces a
Raw Transcript inside Fusion, so it is simply not post-processed. That is a documented coverage
limit, not an architectural conflict.

## 5. Async / session / cancellation rule

The item-4 seam resolves the ASR-side token before text is delivered, but an asynchronous processor
result is delivered *later*, and the existing token machine alone does not prove that such a result
is still owned. Source facts (`VoiceInputSession.kt`):

- `complete(token)` (`:72-76`) → `invalidate()` (`:78-81`) → `generation += 1; state = Idle`.
- `cancel()` (`:54-58`) short-circuits: `if (state == State.Idle) return false`. Once `complete()`
  has run, the session is Idle, so a later cancel does **not** advance `generation`.
- `VoiceInputFlow` inherits that short-circuit: `cancel()` returns at `!session.cancel()`
  (`VoiceInputFlow.kt:130-131`) and `close()` reads the same `false` `wasActive` (`:139-141`).
- `start()` (`:23-28`) and `rebind()` (`:66-70`) do bump `generation` while not Idle;
  `VoiceInputFlow.begin()` goes through `session.start()` (`VoiceInputFlow.kt:77-78`).

So after `onFinal` has called `session.complete(token)`, a user cancellation, an
`onStartInput`/input-target change, or a component `close()` inside the processing window leaves
`generation` unchanged. `generation == token + 1` therefore detects **only** newer-session
supersession. It is not a sufficient ownership proof for a pending processor result, and this
preflight does not record it as confirmed architecture.

**PoC correctness requirement (the seam conclusion of item 4 is unchanged).** An asynchronous
processor result may replace/commit its Raw Transcript only while it still owns the Voice result
**and** the relevant input/editor target remains valid. The PoC must establish the smallest
mechanism consistent with the existing Voice lifecycle for this post-`complete()` window. It may
reuse the existing generation/session mechanisms where they are actually sufficient — newer-session
supersession already is — but must not assume that `generation == token + 1` alone handles
cancellation, component close, or editor/input-target invalidation. The mechanism itself is
deliberately not invented in this evidence pass; it is the first question the PoC has to settle.

Two outcome classes must stay separate because their semantics are opposite:

- **A. Processor execution failure** — exception, timeout, blank/invalid output. If ownership is
  still valid → commit the Raw Transcript. A valid transcript must not be lost because optional
  processing failed.
- **B. Ownership invalidation** — a user cancellation that invalidates this pending result, the
  input/editor target changed or disappeared, a newer Voice result/session superseded it, the
  component closed, or the result is otherwise stale → drop the processed result and do **not**
  commit the old Raw Transcript as a fallback either.

> **Invariant.** Processor failure may fall back to Raw Transcript only while the pending Voice
> result still has valid ownership. Lost ownership means no commit at all.

The eight required scenarios, restated against the real lifecycle:

1. Raw Transcript arrives, processor starts → keep the UI in `State.Stopping`, do not
   `notifyState()` to Idle until the processor resolves (the one observable change: the Idle
   notification is delayed by the processing window).
2. User cancels while processing → nothing may be committed. `generation` does **not** move for
   this case after `complete()`; the PoC mechanism must cover it.
3. Input target disappears/changes → `onStartInput` → `VoiceInputComponent.cancel()` →
   `flow.cancel()` short-circuits once the session is Idle, so the same as (2): must be covered by
   the PoC mechanism, not assumed.
4. Newer Voice session starts → `begin()`/`start()` moves `generation` → the existing comparison
   already makes the old result stale; the new session is unaffected because the processor holds no
   backend reference. This is the one case the current token machine does cover.
5. Old result arrives late → dropped by the same ownership check, log only (observe-only,
   consistent with partial handling).
6. Processor fails → class A: `output.commit(raw)` **only while ownership is still valid**;
   otherwise no commit.
7. Processor times out → same rule as (6), using the already injectable
   `VoiceSessionScheduler.schedule(delayMs)` (`VoiceSessionTiming.kt:63-77`) — the same mechanism
   the watchdog uses, no new timer type.
8. Processor returns empty/blank/invalid → same rule as (6): raw fallback while owned, nothing once
   ownership is lost.

Additional owner-side rule derived from source: the processor must never touch `backend`, `stage`,
`cancelTimer` or `fallback`; it is a text→text collaborator plus an ownership check only.

## 6. Partial vs final policy

Hypothesis **confirmed against source**: only the FINAL Raw Transcript can enter post-processing.
Partials travel `onPartial → updateComposing` and are never committed; and three of the four
provider families deliberately suppress partial visibility (Local ASR "observe only"
`LocalAsrBackend.kt:145`, Doubao `DoubaoAsrBackend.kt:227-234`, network
`NetworkAsrBackend.kt:65-67`), so partials are frequently not even a complete sentence.
Recommendation: keep "final only". No streaming LLM generation — nothing in the current pipeline
produces an incremental final-style text, and streaming would require inventing a second
composing path, which the accepted architecture does not ask for.

## 7. Failure / fallback semantics

```
disabled                    : raw ─────────────────────────────► commit(raw)        (current behaviour)
success (still owned)       : raw ──► processor ──► processed ─► commit(processed)
A. execution failure,
   ownership still valid    : raw ─────────────────────────────► commit(raw)        (never lost)
B. ownership lost           : processed *and* raw are dropped ─► no commit at all
```

- A valid Raw Transcript is never lost because *processing failed* (class A). A transcript whose
  ownership was invalidated (class B) is not committed either: item 5 splits these two outcomes,
  and the PoC must keep them split, because in class B the target that would receive the text may
  no longer exist.
- Processing is not on the ASR-error path at all (`onError` produces no transcript), so no new
  error class is needed and D035 remains untouched: `tryFallback` fires only for a
  `VoiceError.Service` before `established`, and the processor runs strictly after that window.
- **Recommendation for the first PoC**: silent raw fallback (class A only) + one small
  non-blocking hint.
  Current UI infrastructure supports this cheaply (`service.toast` is already used for
  `voice_fell_back_to_local`, `voice_asr_error`, etc., and `VoiceAudit` is an observe-only record
  that can gain one event class). A blocking dialog, a new `VoiceInputSession.State`, or a
  preedit "polishing…" indicator would each mean new lifecycle surface for no correctness gain,
  so they are rejected. The hint must be redaction-clean (`ErrorRedaction`) and must not show
  transcript content.

## 8. Processor abstraction (smallest that covers Disabled / Local / Cloud)

Derived from the project's actual conventions (`VoiceBackend` = interface + main-thread callbacks,
coroutines only inside backends, `VoiceError` sealed class, `VoiceSessionScheduler` for timing):

```kotlin
// orchestration (VoiceInputFlow holds only this)
internal interface TextPostProcessor {
    /** Runs off the main thread; returns processed text, or throws/Times out. */
    suspend fun process(raw: String, languageTag: String): String
}
internal object NoTextPostProcessor : TextPostProcessor { ... }        // disabled: never invoked

// provider implementation — two, no more
internal class LocalTextPostProcessor(cache: TextModelCache, ...)      // owns native runtime
internal class CloudTextPostProcessor(client: ..., timeoutMs)          // owns HTTP + redaction

// provider configuration (separate from ASR configuration)
internal data class TextPostConfig(mode, processorKind, model?, endpoint?, credentialProvider)

// model lifecycle — Local only, separate instance of the existing lease pattern
internal class TextModelCache(loader: (TextModel, Int) -> TextEngine)  // mirrors LocalAsrRecognizerCache
```

Ownership split required by §15: `VoiceInputFlow` = orchestration + the item-5 ownership check; the
processor = provider implementation; `VoiceInputComponent` = configuration + lifetime; the cache =
model lifecycle. `VoiceInputFlow` learns nothing about llama.cpp, LiteRT, ONNX, HTTP or any vendor.

Deliberately **not** proposed: a generic "pipeline stage" framework, a reactive flow rewrite, a
second session state machine, or reusing `VoiceBackend` for text (a `VoiceBackend` is
audio-owning and per-session; the processor is text-only and may be long-lived). Two
implementations over one 1-method interface is the minimum that satisfies
"accommodate Local and Cloud without separate Voice pipelines".

## 9. Existing reusable Fusion infrastructure

(Full classification; file:line evidence in `evidence/infra.md`.)

**A — reusable as-is**
- `KeystoreSecretCipher` + `CredentialStore` (`KeystoreSecretCipher.kt:22-62`,
  `AsrCredentials.kt:39-64`): AES-256-GCM under a non-exportable Keystore key, **one file per
  opaque provider string**, `Map<String,String>` fields, provider bound as AAD, `VERSION = 1`
  prefix. Genuinely provider-agnostic → a text-processor credential store needs only a new
  provider string, e.g. `"llm-openai-compatible"`. This is the "genuine generic credential
  abstraction" that §16 requires before any same-vendor sharing could even be discussed.
- `NetworkAsrBackend.http` / `ModelJobs.http` OkHttp singletons; `ErrorRedaction.redact(detail, secrets)`
  (`ErrorRedaction.kt`); `FileReplace` atomic write; `VoiceSessionScheduler`/`HandlerVoiceSessionScheduler`;
  `AppPrefs` mechanisms (`bool/string/switch/enumList`, `ManagedPreferenceUi`); `service.toast`;
  `VoiceAudit` (observe-only event classes).
- sherpa-onnx **AAR dependency pattern** (`app/build.gradle.kts:145`,
  `settings.gradle.kts:16-27` GitHub-release asset repo, version `sherpaOnnx = 1.13.8`): the
  precedent for how Fusion already ships a native inference runtime in every build variant.

**B — reusable pattern, separate LLM instance/config required**
- ASR provider settings pattern (per-service enable + select + `lastUsedService`/`lastError`) —
  correct shape, new namespace.
- `SelfHostedInstance`/`SelfHostedProtocol`/`endpointProblem(url, allowCleartext, protocol)`
  (`SelfHosted.kt:22-117`) — id/name/url/model JSON + scheme+cleartext validation is exactly the
  endpoint-config shape an LLM needs, but must be its own list (a text-processor endpoint is not
  an ASR instance).
- `ModelCatalogEntry` + `LocalModelInstaller` + `ModelTasks`/`ModelJobs`
  (`LocalModelInstaller.kt:29-65,300-436`, `ModelTasks.kt:25-149`): pinned revision URL or pinned
  archive + per-file SHA-256 + staging + atomic `swapIn` + pause/resume/cancel + license/attribution/
  limitation metadata + resume of partial downloads. Strongly reusable — but see D1 below.
- `VoiceSettingsFragment` programmatic `render()` + `VoiceSettingsRows`/`ModelRows` pure row logic —
  add a section, don't rebuild the screen.
- `LocalAsrRecognizerCache` (`LocalAsr.kt:167-225`) — ref-counted single-entry lease + `loadMillis`
  accounting is the right residency model for a text model, as a second instance.
- `LocalAsrMetrics` (`LocalAsr.kt:228-250`) — the same metric vocabulary (load/decode/stop→final/
  `Debug.getPss()`) is the natural starting shape for post-processor metrics.

**C — unsuitable, because reuse would couple ASR and processor (violates D017/REQUIREMENTS §10)**
- `VoiceBackendKind`, `AsrServiceId`, `VoiceSelection`, `resolveVoiceBackend`/`resolveCurrentService`:
  adding a text processor here would make the processor part of "which ASR service is selected" and
  would silently route it through `fallbackTarget`/D035.
- `LocalAsrBackend`/`NetworkAsrBackend`/`DoubaoAsrBackend` as hosts for processing: they are
  audio-owning and end their existence at `onFinal`.
- Reusing an ASR provider's stored credential record (`"doubao"`, `"qwen"`, `"tencent"`,
  `"selfhosted-<id>"`) for an LLM because the vendor matches: prohibited unless the user
  explicitly configures it that way. Same vendor must not imply shared selection or secrets.
- `AsrResolution`/`VoiceStartStep` gating and `VoiceEndpointPolicy` timeouts: they bound the
  mic/session window, not the text stage; reusing their constants would silently change ASR
  endpointing.

**D — missing, needs minimal new infrastructure**
1. A text-in/text-out async processor contract (item 8) — nothing equivalent exists.
2. A model-artifact identity decoupled from the `LocalAsrModel` enum: the catalog, installer,
   tasks and jobs are all keyed by `LocalAsrModel` (`LocalAsr.kt:15-72`), so either
   `ModelCatalogEntry.model` becomes a small `ManagedModel` id or the text models get a parallel
   enum. The PoC may hard-pin one text model file and defer this generalisation.
3. Native LLM runtime packaging: no llama.cpp/LiteRT in the build today (only the sherpa AAR);
   `app/src/main/cpp/CMakeLists.txt` builds fcitx5 core only. Following the AAR/prebuilt-`.so`
   precedent is the low-risk route.
4. Independent residency policy for two native runtimes (see item 13).
5. Privacy disclosure strings for the "Raw Transcript → remote LLM endpoint" step (REQUIREMENTS §11
   lists it as a required knowable fact; nothing in `strings.xml` covers it yet).

## 10. Android local inference framework comparison (Oct 2026)

Sources: official docs/repos only, URLs + dates in `evidence/frameworks.md`.

| Dimension | llama.cpp / GGUF | LiteRT-LM + LiteRT | ML Kit GenAI / AICore (Gemini Nano) | MLC-LLM / ExecuTorch / ORT-GenAI / vendor SDKs |
|---|---|---|---|---|
| Android integration maturity | CONFIRMED official Android paths: `examples/llama.android`, NDK cross-compile flags, Termux | CONFIRMED `litertlm-android` artifact, Android docs for CPU/GPU/NPU | CONFIRMED Google-supported app API, but service-gated | mixed; MLC Vulkan CONFIRMED, ExecuTorch integration-heavy, ORT-GenAI mobile LLM parity UNVERIFIED |
| Kotlin/JNI boundary | CONFIRMED documented Kotlin binding (`GgufMetadataReader`, `AiChat`→`InferenceEngine`, `Flow`); JNI layering is ours | CONFIRMED Kotlin/Java artifacts | CONFIRMED high-level Kotlin API | UNVERIFIED/case-by-case |
| CPU | CONFIRMED, ARM feature autodetect up to SME2, KleidiAI optional, arm82/dotprod standard | CONFIRMED | n/a (opaque) | CONFIRMED (MLC: not CPU-primary) |
| GPU | OpenCL CONFIRMED for Android NDK build ("recent Adreno"); Mali weak/variable per upstream; **Vulkan exists but no Android guidance → mobile effectiveness UNVERIFIED** | CONFIRMED GPU backend docs | n/a | MLC Vulkan CONFIRMED |
| NPU | `GGML_HEXAGON` exists (Qualcomm Docker toolchain, needs Hexagon SDK) — **phone maturity UNVERIFIED** | **CONFIRMED the only packaged ordinary-app NPU route**: LiteRT `CompiledModel` + vendor delegates (Google Tensor, Qualcomm QNN, MediaTek NeuroPilot, OpenVINO, Exynos); named SoCs incl. **Dimensity 9300** | n/a | QNN/Genie, NeuroPilot real but per-SoC, account-gated; accessibility from a sideloaded APK **UNVERIFIED** |
| Model compatibility | GGUF native; community quantisations for Qwen/MiniCPM | `.litertlm`, converter from HF; Qwen explicitly listed; conversion required | **fixed Google models only; no custom weights** | per-framework |
| Quantisation | CONFIRMED Q4_K_M etc., broad | CONFIRMED (LiteRT quant) | n/a | yes |
| Cancellation | CONFIRMED `llama_set_abort_callback()` **CPU-only** per header comment → GPU/NPU mid-decode abort UNVERIFIED | UNVERIFIED for this workload | n/a (per-call) | UNVERIFIED |
| Memory ownership / warm reuse | CONFIRMED mmap from app-private storage; shared-storage mmap fails (`#18804`, closed) → model must live in app-private dir, exactly where `LocalModels` already installs | CONFIRMED runtime-managed | service-managed, invisible | UNVERIFIED |
| Model loading | mmap-based; **0.5–2 B Q4 load latency on phones: no official number → UNVERIFIED (a PoC measurement)** | UNVERIFIED | n/a | UNVERIFIED |
| Package size | NDK build with only needed backends (CPU-only ≈ few MB) | runtime + vendor delegate libs (larger, Maven-delivered) | 0 (system service) | varies |
| Maintenance burden / upstream activity | CONFIRMED MIT, active; latest tag reported v0.6.0 (2026-10-05) | CONFIRMED Apache-2.0, v0.18.0 (2026-10-06), still 0.x with `ExperimentalFlags` → expect breaking changes | CONFIRMED Google-maintained; Nano 4 in developer preview | CONFIRMED active repos |
| License | MIT | Apache-2.0 | Google terms | Apache-2.0 / BSD-3 / MIT |
| Fit for Fusion | **preferred first**: smallest, controllable, CPU-first matches reality, GGUF ecosystem for the shortlisted models | second evaluation, specifically to test the MediaTek NPU claim on the X100 Pro | **not usable for the PoC**: neither target device is eligible | not first |

Preferred Android inference backend(s): **llama.cpp (GGUF, CPU-first, app-private mmap, own JNI
facade over the documented Kotlin binding)** for the first PoC, with **LiteRT-LM kept as the
NPU-hypothesis test vehicle** only if the PoC needs to answer the NPU question at all.

Strict acceleration position for §11/B5: on a sideloaded Fusion APK, the *verified* execution tier
on both target devices is **CPU**; Adreno OpenCL/Vulkan is **LIKELY/unverified**, and MediaTek
NeuroPilot / Qualcomm Hexagon NPU execution is **UNVERIFIED for Fusion** (silicon capable ≠
app-accessible ≠ measured). NNAPI is deprecated in Android 15 with "most devices are expected to
use the CPU backend" (CONFIRMED, official migration guide). No advertised TOPS figure is treated
as performance evidence.

## 11. Local model shortlist (no downloads in this task)

| # | Model / version | Params | Candidate quant | Approx. storage | License | Redistribution | Chinese suitability | Backend | Distinct experiment question |
|---|---|---|---|---|---|---|---|---|---|
| L1 | **Qwen3-0.6B** (base model, 2025-04) | 0.6 B | GGUF Q4_K_M (community quant, e.g. unsloth/bartowski; not an official Qwen artifact) | ≈0.4 GB download, ≈0.6–0.8 GB resident (estimate, LIKELY) | Apache-2.0 (CONFIRMED) | permissive; app-download + attribution notice | strongest known sub-1B Chinese (LIKELY) | llama.cpp CPU | *is 0.6 B enough for conservative punctuation + zh/en spacing + restrained correction at minimal footprint?* |
| L2 | **MiniCPM5-1B** (OpenBMB, ~2026-08; 1.08 B total / 0.68 B non-embedding, CONFIRMED from card) | ~1 B | Q4 from **official `openbmb/MiniCPM5-1B-GGUF`** | ≈0.7 GB | Apache-2.0 (CONFIRMED) | **official GGUF supply chain** — cleanest provenance; OpenBMB also ships MobileCPM | zh+en, on-device-oriented line (LIKELY) | llama.cpp CPU | *does a purpose-built 2026 on-device model beat Qwen3-0.6B on meaning-preservation at similar cost?* |
| L3 (optional upper bound) | **MiniCPM5-2B** (2026-09-07, LIKELY) *or* **Qwen3.5-0.8B** (2026-02, CONFIRMED Apache-2.0, 262K ctx, natively multimodal) | 1–2 B | Q4 (official/`UD-Q4_K_XL`) | ≈1.3–1.5 GB | Apache-2.0 | community GGUF inherits Apache-2.0 (LIKELY); Qwen3.5 carries vision weights unless a text-only conversion is used | strong (LIKELY) | llama.cpp CPU | *is the 1.5–2 B class reachable at all with Local ASR resident — i.e. is there a quality step worth its memory?* |
| S (specialized non-LLM control) | **FunASR CT-Transformer punctuation** `iic/punc_ct-transformer_cn-en-common-vocab471067-large`, official quantised ONNX (`model_quant.onnx`) + optional **MacBERT-4CSC** (`shibing624/macbert4csc-base-chinese`) | — / ~0.1 B | as published | ≈0.9–1.1 GB + ≈0.4 GB (LIKELY) | Apache-2.0 both (CONFIRMED) | ONNX; already coexists with sherpa-onnx deployments | zh+en, punc-only | ONNX Runtime (already inside the sherpa AAR) | *is an LLM needed at all, given deterministic, no-hallucination edits?* |
| C (cloud control) | any **OpenAI-compatible chat-completions** endpoint, user BYOK, configured independently of the ASR provider | — | — | — | n/a | n/a | upper quality reference | existing OkHttp pattern (`OpenAiTranscriptionClient` shape) | *what does a strong model change on the same corpus, and how much of the local gap is model capacity vs prompt?* |

Dropped with reason: Gemma 3 1B / Gemma 4 2B (custom Google terms, flow-down obligations and
unilateral modification — worse redistribution posture than Apache-2.0, and Chinese is reputedly
weaker at that size = UNVERIFIED); LFM2-1.2B (LFM Open License v1.0 revenue-gated commercial use);
Qwen2.5-0.5B (superseded at similar footprint); SmolLM2/Phi-mini (weak Chinese);
InternLM2.5-1.8B/MobileLLM (non-permissive or research licenses). "Larger is better" is not
assumed: L3 is explicitly framed as a reachability/memory question, not a quality assumption.

## 12. Specialized (non-LLM) model assessment

CT-Transformer punctuation is the only *specialized* class with enough evidence to justify a
control arm, and it is uniquely cheap to host: Fusion already ships sherpa-onnx 1.13.8 in every
build variant (`LocalAsrEngines.kt:19`), and sherpa-onnx deployments exist for exactly this model.
Its edits are deterministic — it cannot reword meaning — so its harmful-modification risk is
limited to wrong punctuation/segmentation, not rewriting. It does not do homophone/context
correction, proper-name normalisation, or filler cleanup; MacBERT-4CSC covers
spelling/homophone correction (SIGHAN-class) as a second deterministic stage.

The decisive unknown is whether punctuation restoration is even the real workload: the two
production X-ASR model archives are named `...-zh-en-punct-int8-...`, i.e. punctuating exports,
while the authority's own qualitative check on the predecessor candidates recorded "均无标点"
(`asr-productization-worklog.md:86`) and the device acceptance rows leave 有无标点 **"未报告"**
(`asr-productization-acceptance.md` §2.14). Self-hosted FunASR 2-pass is verified to return
punctuated finals (`asr-productization-worklog.md:62`, INTEROP-VERIFIED). So punctuation behaviour
is **provider-dependent and partly unmeasured on the current production models** (UNVERIFIED).
Consequence: the PoC must first characterise raw ASR output; if the production Local models
already punctuate, class B loses most of its value and the LLM question narrows to correction and
conservative formatting.

## 13. Local ASR + local processor coexistence risks

Footprint facts read directly from the catalog (`LocalModelInstaller.kt:78-140`, sums of pinned
per-file sizes — CONFIRMED):

| Local ASR model | installed weights | download | notes |
|---|---|---|---|
| `FunAsrNano` | 237,792,748 + 600,356,593 + 155,584,380 + 15,871,340 (tokenizer) = **1,009,605,061 B ≈ 1.01 GB** | same (HF pinned rev `6f16bd37`) | `llm.int8.onnx` + `Qwen3-0.6B/{vocab,merges,tokenizer}` → **this ASR is itself a 0.6B-class LLM decoder** (`LocalAsrEngines.kt:110-123`) |
| `XAsrOffline` | **174,965,025 B ≈ 175 MB** | 136,396,739 B archive | zipformer transducer, `OfflineRecognizer`, `modified_beam_search` |
| `XAsrStreaming960` | **169,225,888 B ≈ 169 MB** | 133,895,831 B archive | streaming zipformer, 960 ms trailing-silence flush |

Risks, each with its source-grounded mechanism:

1. **Memory overlap is the dominant constraint, not CPU.** Pairing any 0.6–1 B GGUF with
   `FunAsrNano` means two ≈0.6 B-class LLMs plus a duplicated Qwen3-0.6B tokenizer in one
   process. `LocalAsrMetrics.processPssMb` (`LocalAsr.kt:238`) already samples PSS with the model
   loaded, but the authority records no reported PSS values for these models (UNVERIFIED) — so
   peak RSS with both loaded is a first-class PoC measurement, on 12 GB units, watch for
   low-memory killer and ImeService-process restart.
2. **Model residency policy conflicts.** `LocalAsrRecognizerCache` keeps exactly **one** loaded
   recognizer keyed by (model, threads) with ref-counted leases, and `VoiceInputComponent`
   explicitly releases it whenever the selection is not Local (`releaseLocalAsr()`,
   `VoiceInputComponent.kt` "a cached Local ASR model (about 1 GB for FunASR Nano) is not kept once
   Local is off"). A text processor therefore needs its own cache instance **and** an explicit
   two-runtime policy — options to measure, not to decide now: (a) both resident, (b) evict ASR
   before loading the text model (costs ASR cold load on the next utterance), (c) keep the text
   model out of the IME process entirely (rejected for the PoC: no second process is justified by
   source yet).
3. **CPU contention is real and already configured against us.** Local ASR runs with
   `localAsrThreads` clamped 1..4, default **2** (`AsrSelection.kt:456-459`), on sherpa/ONNX
   Runtime; an LLM decode wants the big cores simultaneously. `XAsrOffline`/`FunAsrNano` decode
   happens *before* `onFinal`, so the two stages are sequential in the ideal case — the contention
   case is a thermal soak, not an overlap.
4. **Latency interaction: the IME-visible window is the sum.** Users already experience
   stop→final decode (`stopToFinalMillis`, logged). Post-processing appends to that window, and
   during it the keyboard is in `Stopping` with **nothing in the editor yet** (the raw text is not
   pre-committed, per item 5). Cold load adds a first-utterance penalty. This is why item 14's
   metrics must be measured end-to-end from stop, not as standalone LLM throughput.
5. **GPU/NPU contention:** irrelevant to a CPU-only PoC; if LiteRT-LM NPU were tested, the
   APU/HTP is shared with camera/voice-assistant system features and vendor delegate availability
   is UNVERIFIED on both国行 devices.
6. **Storage/distribution:** a text model is 0.4–1.5 GB on top of an already-installed ASR model;
   `LocalModels` installs into `noBackupFilesDir/local-asr` (`LocalModels.kt:30`), so a text model
   needs its own root (e.g. `noBackupFilesDir/local-text`), a pinned catalog entry with SHA-256
   (D059 four-rights pattern already encoded per entry: `sourceUrl`, `license`, `attribution`,
   `limitation`), and its own "download offered" gate (`downloadOffered(BuildConfig.DEBUG)`).

## 14. Future benchmark corpus and metrics (designed here, not run)

Test buckets (as required): **short ≈20–30**, **medium ≈50–100**, **long ≈200–300** Chinese chars,
~8 samples each bucket per candidate.

Corpus design — 8 categories × 5 items ≈ 40 items, each with a reference "acceptable output" and a
"must-not-do" note. Items must be produced from real ASR output (or a simulator) rather than
typed text, so ASR-style error distribution is preserved:
1. missing punctuation (run-on finals), 2. long run-on multi-clause text, 3. common Chinese
   homophone mistakes (`帐号/账号`, `期目/项目`-class, contextual `的/地/得`), 4. Chinese + English
   technical terms (`gradle 构建失败`, `PR 还没合`), 5. product/project names (`fcitx5-android`,
   `Fusion Enhanced`, `OriginOS`), 6. numbers and dates (`2026 年 10 月 13 日`, `3.5 版本`, phone-like
   strings — must be preserved verbatim), 7. already-correct transcripts (must be returned
   **byte-identical**), 8. ambiguous sentences where conservative "do not change" is preferable.

Metrics, both directions as required:
- **A. useful corrections**: per-category correction recall (punctuation inserted correctly,
  segmentation, spacing, homophone fixed), and *no-op correctness* on category 7.
- **B. harmful / unnecessary modifications**: edit-classification on every diff —
  `{punctuation insertion, spacing, substitution, deletion, reordering, content insertion}`;
  **content insertion or any deletion of a number/name is a hard fail**; harmful-modification rate
  and over-edit index (edits per 100 chars on already-correct items); meaning-preservation
  adjudication (2-label human check: preserved / changed).
- Deterministic scoring helpers (JVM-testable, no device): character-level edit distance to
  reference, edit-class classifier, identity check on category 7.
- Latency/memory per §12 of the task: cold-load latency, warm request latency, time-to-first-output,
  end-to-end **from stop to commit**, input/output token and char counts, peak RSS, steady resident
  memory, coexistence with the selected Local ASR model (both loaded), repeated-session thermal
  behaviour, energy if practical, plus output correctness / unwanted semantic changes.

**Provisional decision bands** (deliberately not product requirements; they exist so the PoC can
stop and decide):
- Warm end-to-end processing: **good ≤ 1.5 s**, **marginal 1.5–4 s**, **poor > 4 s**.
  Rationale: the current pipeline already tolerates an 8 s provider final timeout
  (`NetworkAsrBackend.DEFAULT_FINAL_TIMEOUT_MS = 8_000`) and 20 s for self-hosted offline passes;
  a *local* stage should sit well inside the worst case the user already accepts, and >4 s of an
  empty editor after the user stopped speaking reads as a failure.
- Long bucket (200–300 chars): **good ≤ 6 s**, **poor > 10 s** — beyond that, chunking or
  "disable for long transcripts" is the right product answer, not a faster model.
- Cold load: record only, bands set after the first measurements; anything that makes the *first*
  utterance of a session feel broken is a residency-policy finding, not a model finding.
- Memory: no band is proposed before measurement; the decision rule is "the two loaded models must
  not plausibly push the IME process into the low-memory kill range on a 12 GB device".
- Quality gate for ANY candidate: harmful-modification rate on categories 6–8 must be ~0 and
  category 7 must be byte-identical, because "polished prose that changes meaning is a failure".

## 15. Recommended first PoC — ONE bounded experiment

**"Seam + two-model CPU feasibility PoC."** It answers the highest-value unresolved question:
*can an Apache-2.0 ~0.6–1 B local model, on the real IME seam and on the real devices, produce a
conservative improvement of a short Chinese ASR transcript within an IME-tolerable delay, without
losing a valid transcript?* — which is exactly what gates every later decision.

- Local candidates: **L1 Qwen3-0.6B Q4_K_M (community quant)** and **L2 MiniCPM5-1B Q4 (official
  GGUF)**;
  L3 (MiniCPM5-2B / Qwen3.5-0.8B) only if both L1/L2 pass quality and fail latency, to locate the
  memory ceiling. Maximum 3, one at a time per run.
- Inference backend: **llama.cpp, CPU only, GGUF mmap from app-private storage**, behind a thin
  Kotlin facade; NPU/GPU explicitly out of scope (UNVERIFIED access).
- Cloud control: one OpenAI-compatible BYOK endpoint, **same prompt, same corpus**, used only to
  separate "model capacity" from "prompt/task design"; never mixed into ASR configuration.
- Architecture under test: the item-4 seam in `VoiceInputFlow.onFinal` with the item-8 interface
  and the item-5 ownership requirement — PoC-scoped, debug-gated pref switch, no settings UI beyond
  an internal toggle.
- Device matrix (small on purpose): **vivo X100 Pro** (Dimensity 9300, 12/16 GB, OriginOS) and
  **Redmi K90 Pro Max** (SD 8 Elite Gen 5, 12/16 GB, HyperOS); each with **XAsrOffline** as the
  co-resident ASR (the ≈175 MB model), and **FunAsrNano only on one device/one run** to measure
  the double-LLM worst case.
- Transcript buckets: 20–30 / 50–100 / 200–300 chars, ~8 items each, from real ASR output; the
  8 corpus categories of item 14.
- Measurements: item 14's full latency/memory set (stop→commit as the headline number), plus
  quality A/B metrics; log via the existing `LocalAsrMetrics`-shaped summary so numbers are
  comparable with existing ASR logs.
- **Decision rule** (all three must be satisfied to proceed to a product proposal):
  1. at least one local candidate reaches **good/marginal** warm latency in the medium bucket on
     both devices, with end-to-end stop→commit inside the bands of item 14;
  2. harmful-modification rate ≈0, category 7 byte-identical, zero content insertions;
  3. useful-correction recall clearly beats the deterministic-rule baseline on categories 1–4.
  → If 1 fails but 2–3 pass: the answer is Cloud-first (or a chunking/residency redesign), not a
  bigger model. If 2 fails at any size: local LLM post-processing is not the right capability and
  the specialized non-LLM control (item 12) becomes the product question. If 3 fails for
  everything: the seam is right but the workload is wrong → stop the LLM line.
- Prerequisite implementation inside the same PoC (before the model numbers are interpreted): the
  smallest mechanism that satisfies the item-5 ownership rule for the post-`complete()` window.
  Newer-session supersession can reuse `generation`; cancellation, component close and
  editor/input-target invalidation need whatever the existing lifecycle actually requires. This is
  PoC plumbing scoped to the experiment, not a product state machine, and its final form is not
  decided by this preflight.
- Prerequisite measurement inside the same PoC (half a day, not a separate campaign): characterise
  whether the current production Local ASR finals already carry punctuation on-device, because
  that single observation decides whether class B/CT-Transformer remains in scope.
- Explicit non-goals: no benchmark grid over quantisations × threads × backends × devices, no
  NPU experiments, no prompt-engineering campaign, no settings UI, no long-soak thermal study.

## 16. Expected implementation boundary (for eventual integration)

Likely to change:
- `input/voice/VoiceInputFlow.kt` — the processing branch in `onFinal`, the item-5 ownership check,
  a processor collaborator, one new `Output` no-op? (no: keep `Output` unchanged; the branch calls
  `output.commit` on resolution).
- `input/voice/VoiceInputComponent.kt` — construct/hold the processor + model cache, read prefs,
  release policy alongside `releaseLocalAsr()`.
- `data/prefs/AppPrefs.kt` — 2–4 new prefs (enable switch, processor kind, model id, endpoint/
  credential provider string).
- `ui/main/settings/behavior/VoiceSettingsFragment.kt` + `VoiceSettingsRows.kt` (+ a rows file for
  the new section) — one added section in the existing programmatic `render()`.
- `input/voice/VoiceAudit.kt` — new observe-only event classes (processor used, outcome class,
  latency bucket) — never transcript content.
- `res/values*/strings.xml` — switch titles/summaries, the two disclosure strings (local vs remote),
  the failure hint.
- `app/build.gradle.kts` / `settings.gradle.kts` / new native module or prebuilt AAR — runtime
  packaging, following the sherpa-onnx AAR precedent.

New files/classes (all inside `input/voice/` + a runtime package): `TextPostProcessor.kt`
(interface + `NoTextPostProcessor`), `LocalTextPostProcessor.kt`, `CloudTextPostProcessor.kt`,
`TextPostConfig.kt`, `TextModelCatalog`/`TextModel` id, `TextModelCache.kt`, a JNI/Kotlin facade
over the runtime, plus catalog/pinning for text models.

Tests: extend `VoiceInputFlowTest` (its `FakeBackend`/`FakeOutput` harness already supports
synchronous terminal events, `VoiceInputFlowTest.kt:19-40`) with the eight scenarios of item 5,
which must include the class A (execution failure, still owned → raw) versus class B (ownership
lost → no commit) split;
new JVM tests for config/ownership/timeout, prompt building and response parsing, edit-classification
scoring; `CredentialStore`-style tests for the new provider string. No instrumentation farm and no
CI expansion are implied.

Conditionally in scope: `input/voice/VoiceInputSession.kt`. Its token machine covers newer-session
supersession, but per item 5 it does not cover the post-`complete()` ownership window. If the
smallest mechanism the PoC settles needs a marker there, `VoiceInputSession` and/or
`VoiceInputFlow` change accordingly. This preflight neither claims that no session change is
required nor prescribes one.

Components expected **NOT** to change — stated explicitly as required:
- **individual ASR providers**: none (`SystemAsrBackend`, `NetworkAsrBackend`, `DoubaoAsrBackend`,
  `LocalAsrBackend`, the four self-hosted clients, `QwenAsrClient`, `TencentAsrClient`) — source
  proves every one already converges on the single seam (item 3), so per-provider modification is
  unnecessary;
- **Voice triggers**: unchanged (`VoiceTrigger`, toolbar button, long-press Space, `toggle()`,
  `startVoiceInput`/`stopVoiceInput`);
- **D035 fallback**: unchanged (`tryFallback`, `rebind`, `fallbackTarget`, `VoiceEndpointPolicy`
  constants) — the processor runs strictly after the fallback window;
- **Local ASR engines**: unchanged (`LocalAsrEngines`, `LocalAsr.kt`, `LocalAsrBackend`);
- **LibIME**, **fcitx5-chinese-addons**, native Chinese input code, the fcitx5 core CMake build,
  `FcitxInputMethodService.commitText`/`updateVoiceComposingText`: unchanged — the processor hands
  text to the existing commit path only.
- `AudioCapture`, `CaptureStats`, `ErrorRedaction` semantics: unchanged.

No unexpected need to modify any of the above is visible in source; if a later batch claims one is
required, that is a Hard Stop review trigger, not an implementation detail.

## 17. Privacy / data-flow implications

- REQUIREMENTS §11 already enumerates the required knowable facts, including "ASR 返回的 Raw
  Transcript / **是否继续发送给 LLM** / **LLM Provider/endpoint** / 最终提交给 IME 的文本". The
  recommended design satisfies this with one rule: the data-flow disclosure must name the
  *recipient of the text*, separately from the recipient of the *audio*.
- **Local processor**: Raw Transcript never leaves the device; no network call. Still disclose
  that the transcript is written to/read from app-private storage for model files, and that
  logs are content-free (the code already logs char counts, not text, for Local ASR partials).
- **Cloud processor**: a *second*, independent disclosure — "your recognized text will be sent to
  `<endpoint>`" — must be shown and accepted at configuration time, not inferred from the ASR
  disclosure. This is a genuinely new upload path: today the ASR upload ends at `releaseBackend()`.
- The **same-vendor trap** is explicitly prohibited by design (item 9/C): an ASR credential record
  must not be reused for the LLM. The `CredentialStore` provider-string abstraction makes an
  independent LLM record the cheap, default choice.
- Cancellation semantics carry a privacy consequence worth stating: audio already streamed to a
  remote ASR cannot be retracted (documented at `VoiceSessionTiming.kt:38-42` and
  `VoiceInputFlow.kt:279-281`); by symmetry, a Raw Transcript already sent to a remote LLM cannot
  be retracted, so the PoC must not enable a cloud processor without that disclosure.
- `VoiceAudit` remains observe-only (records labels, never user content) — the processor must keep
  that discipline, including on failure paths (`ErrorRedaction.redact(detail, secrets)`).

## 18. License / distribution findings

- Shortlisted local models are **Apache-2.0** (Qwen3, Qwen3.5, MiniCPM5, CT-Transformer punc,
  MacBERT-4CSC — CONFIRMED from model cards/mirrors). Community GGUF quants inherit Apache-2.0
  (LIKELY). MiniCPM5 has **official GGUF repositories**, the cleanest provenance chain.
- Dropped for licensing reasons: Gemma 3/4 (custom Google terms with flow-down obligations and
  unilateral modification), LFM2 (revenue-gated commercial license), MobileLLM (research license),
  some InternLM variants (non-permissive).
- This matches the project's existing D059 model-distribution discipline, already encoded per
  catalog entry (`ModelCatalogEntry` fields `sourceUrl`, `license`, `licenseUrl`, `attribution`,
  `limitation`, plus the "audit-pending" note on FunASR Nano whose exporter repo has no LICENSE,
  `LocalModelInstaller.kt:75-77,92-95`). A text model must get the same four-rights record before
  it can be offered for download, and the same pinned-revision + SHA-256 verification.
- llama.cpp is MIT, LiteRT-LM Apache-2.0: both compatible with the LGPL-2.1-or-later app
  contributions model used here (separate library, dynamic/prebuilt linkage — same posture as the
  existing sherpa-onnx AAR). Runtime license review is a normal future step, not a blocker found
  in this preflight.

## 19. Unresolved facts (to be settled by the PoC, not by more desk research)

1. Whether the two production X-ASR `...-zh-en-punct...` models actually emit punctuation on
   device (**UNVERIFIED**; predecessor candidates were recorded "均无标点", and device acceptance
   left 有无标点 unreported). Decides whether punctuation restoration is the workload at all.
2. Measured cold-load and warm decode latency of a 0.6–1 B Q4 GGUF on Dimensity 9300 and
   SD 8 Elite Gen 5 CPU, with a Local ASR recognizer resident (**UNVERIFIED** — no official
   numbers exist; community claims are not evidence).
3. Actual PSS/RSS of the current Local ASR models with the model loaded: the field is logged
   (`LocalAsrMetrics.processPssMb`) but no value is reported in authority evidence (**UNVERIFIED**).
4. Whether conservative behaviour (no rewording) is achievable at 0.6 B with a strict prompt, or
   only at ≥1.5 B at unacceptable memory (**UNVERIFIED**).
5. Whether llama.cpp's CPU-only abort path is sufficient for the item-5 ownership rule (header comment says
   the abort callback is CPU-only — CONFIRMED as a limitation; measured responsiveness UNVERIFIED).
6. NPU accessibility from a sideloaded Fusion APK on these two国行 devices via LiteRT vendor
   delegates (MediaTek NeuroPilot named for D9300; Qualcomm QNN/Hexagon) — **UNVERIFIED**;
   advertised TOPS ("30 TOPS" APU 790, "100 TOPS" Hexagon) are marketing, not performance.
7. Gemini Nano / ML Kit GenAI availability: **CONFIRMED absent** for both target devices (X100 Pro
   is not on the supported list; 国行 firmware lacks GMS/AICore) — so it is not a Fusion backend
   option for this device pair.
8. Thermal throttling effect on repeated voice inputs: only secondary/qualitative reports exist
   (**LIKELY** that sustained load degrades; **UNVERIFIED** for this workload).
9. Whether `Qwen3.5-0.8B`'s multimodal weights can be stripped for a text-only GGUF without
   quality loss (**UNVERIFIED**).
10. The smallest mechanism that owns a pending processor result after `session.complete(token)` for
    cancellation, component close and editor/input-target invalidation — `generation` alone does not
    cover these (item 5). **UNPROVEN by desk research; to be established by the PoC**, which must
    also show the class B rule (no commit once ownership is lost) against the real IME lifecycle.

## 20. Hard Stop status

**No Hard Stop.** All three Hard-Stop examples from §21 are disproved by source:
- a common Raw Transcript seam **does** exist and is a single already-tested function
  (`VoiceInputFlow.onFinal`), so no provider needs invasive modification;
- async processor ownership does **not** conflict with the current session lifecycle: the seam and
  the accepted cancel/D035 semantics need no parallel session machine, and the open part is the
  post-`complete()` ownership window (items 5 and 19) — an unresolved mechanism the PoC must
  establish, i.e. ordinary work, not an architecture-level conflict. What this preflight no longer
  claims is that `VoiceInputSession.generation` alone already solves it;
- a Local runtime does **not** conflict with the current native architecture — the app already
  ships and leases a native inference runtime (sherpa-onnx AAR) from app-private storage, and the
  open items (JNI plumbing, packaging, model download, unverified acceleration) are explicitly
  ordinary work, not architecture conflicts.

Authority check: the design does not assume a parallel state machine; if the PoC needs an
additional ownership marker for the post-`complete()` window, it must be the smallest one
consistent with the existing Voice lifecycle (item 5). The work stays inside the
`fcitx5-android` voice module boundary of REQUIREMENTS §12.

## 21. Final recommendation

# PROCEED TO LOCAL/ARCHITECTURE POC

Rationale in one paragraph: the seam is already correct and provider-neutral, so *where* processing
hangs off the pipeline is answered by construction and needs only a small validation batch; the two
real unknowns are the smallest ownership mechanism for the post-`complete()` window (item 5) and
whether a conservative ~0.6–1 B Apache-2.0 model can, on-device and co-resident with
Local ASR, beat deterministic cleanup inside an IME-tolerable delay. That is exactly the bounded
experiment in item 15, and its three-part decision rule can end the LLM line cleanly if it fails.
A cloud-first PoC would answer a question the accepted architecture does not make contingent
(cloud post-processing is a configuration choice, and the cloud client shape already has a
template in `OpenAiTranscriptionClient`), while a hybrid PoC would double the matrix without
resolving the binding constraint, which is local latency and meaning preservation.
