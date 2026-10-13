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
  (`VoiceInputSession.kt:79`) → any later `onFinal` fails `complete(token)`.
- `onStartInput` → `VoiceInputComponent.cancel()` → same path.
- `onError` → `tryFallback` (`VoiceInputFlow.kt:213`): only `!established && error is
  VoiceError.Service`, `session.rebind(token)` (new generation, state kept, refused while
  `Stopping`), `fallback = null`, `output.fellBack`, restart on the Local model.
  `Capture`/`PermissionDenied`/`Silent`/`System` errors never fall back.
- Provider failure ⇒ no transcript at all (the transcript was never produced), so
  post-processing is not on this path.

## A6 common seam verdict

One seam exists and is sufficient: insert the optional text post-processing between
`session.complete(token)`/`releaseBackend()` and `output.commit(text)` inside
`VoiceInputFlow.onFinal`. No per-provider code, no provider coupling. The external-voice-IME
provider is out of scope by construction (D055).

## A7 ownership rule, reusing the existing token machine

`session.complete(token)` sets `generation = token + 1` and `state = Idle`. Therefore:

> A processor result may replace/commit its Raw Transcript only if it can still prove
> `generation == token + 1 && !superseded`, checked on the main thread at delivery time.

Any `begin()` (new session), `cancel()`, `close()`, or `onStartInput` advances `generation`
again, so a late processor result becomes stale with no new state machine. Mapping of the
eight required scenarios:

| Scenario | Required behaviour | Mechanism |
|---|---|---|
| cancel while processing | abandon result, nothing committed | `generation` moved by `cancel()` → claim fails; `output.clearComposing()` already done by `cancel()` |
| editor target / input field changed | abandon | `onStartInput` → `VoiceInputComponent.cancel()` |
| newer session started | old result dropped, new session unaffected | claim compares against the newest generation |
| late result after claim lost | drop, log only | claim check on main thread |
| processor failure | commit the Raw Transcript | try/catch → `output.commit(raw)` |
| processor timeout | commit the Raw Transcript | reuse `VoiceSessionScheduler.schedule(timeoutMs)` (same injectable scheduler as the watchdog) |
| empty/invalid processor output | commit the Raw Transcript | blank/non-null validation before replacing |
| processing while UI shows state | keep `State.Stopping` | `onFinal` already ends the gesture; no new state |

## A8 partial vs final

Hypothesis CONFIRMED. Partials reach the editor only through `output.updateComposing`
(`VoiceInputFlow.kt:175-179`) and only for providers that forward them; System ASR does
(`SystemAsrBackend.kt:80`), while Local ASR (`LocalAsrBackend.kt:145` "observe only") and
Doubao/network (`NetworkAsrBackend.kt:66`, `DoubaoAsrBackend.kt:228`) deliberately log
partials and never show them. Nothing partial should enter text post-processing; only the
FINAL Raw Transcript at the seam does. No streaming LLM generation is warranted by the source.

## A9 disabled / success / failure semantics

- Disabled (default): exact Fusion.9 behaviour — `output.commit(raw)`.
- Success: `output.commit(processed)`; if processed is blank/invalid → raw.
- Failure/timeout/exception: `output.commit(raw)` and a redacted `Timber` log; a valid
  transcript is never lost because optional processing failed.
- Recommendation for the first PoC: silent raw fallback plus a low-cost non-blocking hint
  (short toast reusing `service.toast`, `R.string`-backed, no new state), because "the text
  I got is not what I said" vs "polishing silently didn't happen" are indistinguishable to
  the user; a blocking error dialog or a new session state is not justified.
