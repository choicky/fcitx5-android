# Part A — actual Fusion.9 voice source path (HEAD 11a7b4f0)

All references are readable in the research worktree
`/tmp/fcitx5-android/llm-postprocessor-preflight/repo`. Verified by reading executable
code, not class names or docs.

## A1–A3 terminal-event ownership per path

| Path | Backend | Where Raw Transcript becomes final | Provider ownership ends | Converges on |
|---|---|---|---|---|
| A System ASR | `SystemAsrBackend.kt:75` `onResults` | `events.onFinal(token, transcript)` after `release()` (destroy) | inside the listener, before the call | `VoiceInputFlow.onFinal` |
| B Local ASR (XAsrOffline / XAsrStreaming960 / FunAsrNano, all `production = true`, `LocalAsr.kt:32,45,56`) | `LocalAsrBackend.kt` | `loader.join()` → `target.finish()` → `post { events.onFinal(token, text) }` (`LocalAsrBackend.kt:228`); `post{}` drops the event when `cancelled` | lease returned to `LocalAsrRecognizerCache` in `finally` (`LocalAsrBackend.kt:112`) | `VoiceInputFlow.onFinal` |
| C Doubao (product BYOK + debug PoC) | `DoubaoAsrBackend.kt:247` `finish(Outcome.Final)` | `release(graceful=true)` then `events.onFinal(token, outcome.text)` | before the call | `VoiceInputFlow.onFinal` |
| C Qwen / Tencent / Self-hosted ×4 protocols | `NetworkAsrBackend.kt` listener `onFinal` | `release()` then `events.onFinal(token, text)`; finals may arrive up to the per-provider final timeout (8 s / 20 s self-hosted / 60 s OpenAI-compatible, `VoiceInputComponent.kt:421`) | before the call | `VoiceInputFlow.onFinal` |
| (non-production) capture probe | `CaptureProbeBackend.kt:93` | `events.onFinal(token, null)` — never text | — | `VoiceInputFlow.onFinal` → blank branch |
| External Android Voice Input (Model A / D055) | none | n/a — `VoiceInputComponent.startExternalVoiceInput()` switches IME, no `VoiceInputFlow`, no token | — | **never reaches the seam** |

Every one of these calls is `VoiceBackend.Events.onFinal(token: Long, text: String?)`, and
`VoiceInputFlow` is the only consumer (`VoiceInputFlow.kt:21` implements `VoiceBackend.Events`;
`VoiceInputComponent` hands `this` to `backend.start`, `VoiceInputFlow.kt:111`).

## A4/A5 the single convergence point

`VoiceInputFlow.kt:181-191`:

```kotlin
override fun onFinal(token: Long, text: String?) {
    if (!session.complete(token)) return   // token ownership resolved here, once
    disarm()                               // watchdog (D035/NoSpeech/Silence) off
    releaseBackend()                       // backend.cancel() + backend = null
    if (text.isNullOrBlank()) output.clearComposing() else output.commit(text)
    notifyState()
}
```

Downstream: `VoiceInputComponent` `Output.commit` → `voiceAudit.record(CommittedEvent)` +
`service.commitText(text)` → `FcitxInputMethodService.commitText` (`:427`) →
`currentInputConnection.commitText` (composing, if any, is replaced by the committed text;
`updateVoiceComposingText` = underlined composing at `:460`).

Cancellation/failure/fallback:
- `cancel()`/`close()` → `session.cancel()` → `invalidate()` → `generation += 1`
  (`VoiceInputSession.kt:79`) → any later `onFinal` fails `complete(token)`. This holds only while
  the session is not already Idle: `cancel()` returns `false` immediately at
  `if (state == State.Idle)` (`VoiceInputSession.kt:54-58`), and `complete()` has set Idle — see A7.
- `onStartInput` → `VoiceInputComponent.cancel()` → same path.
- `onError` → `tryFallback` (`VoiceInputFlow.kt:213`): only `!established && error is
  VoiceError.Service`, `session.rebind(token)` (new generation, state kept, refused while
  `Stopping`), `fallback = null`, `output.fellBack`, restart on the Local model.
  `Capture`/`PermissionDenied`/`Silent`/`System` errors never fall back.
- Provider failure ⇒ no transcript at all (the transcript was never produced), so
  post-processing is not on this path.

## A6 common seam verdict

One seam exists and is the sufficient *insertion point*: optional text post-processing between
`session.complete(token)`/`releaseBackend()` and `output.commit(text)` inside
`VoiceInputFlow.onFinal`. No per-provider code, no provider coupling. The external-voice-IME
provider is out of scope by construction (D055). What this seam does not settle by itself is
ownership of an asynchronous result delivered after `complete()` — see A7.

## A7 ownership of an asynchronous processor result

`session.complete(token)` (`VoiceInputSession.kt:72-76`) → `invalidate()` (`:78-81`) sets
`generation = token + 1` **and `state = Idle`**. Because `cancel()` short-circuits while Idle
(`:54-58`), and `VoiceInputFlow.cancel()`/`close()` inherit that `false` result
(`VoiceInputFlow.kt:130-131`, `139-141`), the events that happen *after* the final has been
accepted — user cancellation, `onStartInput`/input-target change, component `close()` — leave
`generation` unchanged. Only `session.start()` (`:23-28`, reached from `VoiceInputFlow.begin()` at
`VoiceInputFlow.kt:77-78`) and `rebind()` (`:66-70`) move it.

So `generation == token + 1` proves exactly one thing in the post-`complete()` window: no newer
session has started. It is **not** a sufficient ownership proof for an asynchronous processor
result, and this evidence pass does not record it as confirmed architecture.

**PoC correctness requirement.** An asynchronous processor result may replace/commit its Raw
Transcript only while it still owns the Voice result **and** the relevant input/editor target
remains valid. The PoC must establish the smallest mechanism consistent with the existing Voice
lifecycle for that window — reusing `generation` where it genuinely suffices, and adding whatever
minimal marker cancellation, close and target change actually require. The mechanism is not
designed here.

Outcome classes must stay separate: **A. execution failure** (exception/timeout/blank output) →
commit the Raw Transcript *only while ownership is still valid*; **B. ownership invalidation**
(cancelled, target changed or gone, superseded, component closed, otherwise stale) → drop the
processed result and do **not** commit the old Raw Transcript either.

| Scenario | Required behaviour | Status against the current lifecycle |
|---|---|---|
| cancel while processing | abandon result, nothing committed | NOT covered by `generation` after `complete()`; PoC mechanism required (class B) |
| editor target / input field changed | abandon | `onStartInput` → `VoiceInputComponent.cancel()` → `flow.cancel()` short-circuits when Idle; PoC mechanism required (class B) |
| newer session started | old result dropped, new session unaffected | covered: `begin()`/`start()` moves `generation` |
| late result, ownership lost | drop, log only | class B — no commit of processed text and no raw fallback |
| processor failure | commit the Raw Transcript **while still owned** | class A: try/catch → `output.commit(raw)`; otherwise no commit |
| processor timeout | same as failure | class A: reuse `VoiceSessionScheduler.schedule(timeoutMs)` (same injectable scheduler as the watchdog) |
| empty/invalid processor output | same as failure | class A: blank/non-null validation before replacing |
| processing while UI shows state | keep the UI in `State.Stopping` | `onFinal` already ends the gesture; no new state |

## A8 partial vs final

Hypothesis CONFIRMED. Partials reach the editor only through `output.updateComposing`
(`VoiceInputFlow.kt:175-179`) and only for providers that forward them; System ASR does
(`SystemAsrBackend.kt:80`), while Local ASR (`LocalAsrBackend.kt:145` "observe only") and
Doubao/network (`NetworkAsrBackend.kt:66`, `DoubaoAsrBackend.kt:228`) deliberately log
partials and never show them. Nothing partial should enter text post-processing; only the
FINAL Raw Transcript at the seam does. No streaming LLM generation is warranted by the source.

## A9 disabled / success / failure semantics

- Disabled (default): exact Fusion.9 behaviour — `output.commit(raw)`.
- Success, ownership still valid: `output.commit(processed)`; if processed is blank/invalid → raw.
- A. Execution failure/timeout/exception **while ownership is still valid**: `output.commit(raw)`
  and a redacted `Timber` log — a valid transcript is never lost because optional processing failed.
- B. Ownership invalidated (cancelled, target changed or gone, superseded, component closed):
  drop the result and commit nothing — not the processed text and not the Raw Transcript. Once the
  target is gone, a late commit is the wrong outcome, not a safety net.
- Recommendation for the first PoC: silent raw fallback for class A plus a low-cost non-blocking hint
  (short toast reusing `service.toast`, `R.string`-backed, no new state), because "the text
  I got is not what I said" vs "polishing silently didn't happen" are indistinguishable to
  the user; a blocking error dialog or a new session state is not justified.
