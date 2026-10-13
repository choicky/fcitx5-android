# Local Text Post-Processor PoC (Gate A + Gate B)

Status: **PoC / experimental — not productization.** Decision evidence for REQUIREMENTS §10
(D017: ASR stays Audio → Raw Transcript; the post-processor is a separate, fully closable
stage) and DECISIONS D017/D035. No Settings UI, no model manager, no bundled model, no
provider change; the stage is off unless a model file is deliberately sideloaded into a
debug build.

## What this contains

### Gate A — ownership of the pending result (Q1)

`VoiceInputFlow` alone owns an accepted final while post-processing runs: a flow-local
`processingToken` marker armed in `onFinal`, cleared by exactly `begin`/`cancel`/`close`
(newer session, explicit cancel, input-target change, teardown), plus the existing
`VoiceSessionScheduler` for the 4 s window (`TextPostPolicy`). `VoiceInputSession` is
deliberately untouched — `complete()` already drops it to Idle, which is precisely why the
marker lives in the flow. Outcome classes: owned success → processed text; owned failure
(exception/timeout/blank) → Raw Transcript; lost ownership → nothing committed at all.
15 new JVM tests in `VoiceInputFlowTest` map 1:1 to the required lifecycle matrix
(success/exception/timeout/blank/cancel/target-change/close/newer-session/late-result/
disabled, final-only, D035 untouched).

### Gate B — bounded llama.cpp CPU runtime (Q2)

- `app/src/main/cpp/llama.cpp` — submodule pinned at **v0.6.0** (`d8123504`), MIT.
- `app/src/main/cpp/poc-postprocessor/` — CPU-only static build (no GPU/NPU, no OpenSSL,
  no server/tools) linked into the single `libpoc-postprocessor.so`; created only for
  `arm64-v8a` and requested from Gradle only for `BUILD_ABI=arm64-v8a`, so every other
  build produces the previous native output.
- JNI facade `poc_postprocessor.cpp` mirrors the host probe exactly: mmap load, ctx 2048,
  batch 1024, threads 4, jinja chat template with `enable_thinking=false`, greedy decode,
  generation bounded by an 8 s wall-clock abort callback; single-flight supersede aborts
  a still-running generation when a newer final arrives.
- `LlamaPostProcessor.kt` implements the `TextPostProcessor` boundary: debug builds only,
  exactly-once main-thread callback, `null` (→ Raw Transcript) on any failure, Chinese
  transcripts only, and the frozen v1 prompt (byte-identity asserted by a unit test:
  982 bytes, sha256 `9517338…c79c`). On/off switch = presence of the single sideloaded
  `*.gguf` under the debug app's `<external files>/postproc/`.
- Wiring is one constructor argument in `VoiceInputComponent`:
  `LlamaPostProcessor.poc { service }`.

## Host evidence summary (full data in the task workspace, not the repo)

40-item constructed corpus, 8 categories × short/medium/long, deterministic char-diff
oracle (hard failures = non-punctuation insert/delete/replace, protected-substring loss,
non-identical already-correct):

| run | hard-fail items | never-EOS | already-correct identical | useful punctuation |
| --- | --- | --- | --- | --- |
| baseline raw | 0/40 | 0 | 5/5 | 0/11 |
| baseline mechanical | 2/40 | 0 | 5/5 | 11/11 |
| Qwen3-0.6B Q4_K_M | 19/40 | 0 | 1/5 | 1/11 |
| MiniCPM5-1B Q4_K_M | 28/40 | 10/40 | 1/5 | 2/11 |

Runtime mechanics on host (same pinned revision): deterministic under greedy decoding,
abort callback honored in 0.6 ms mid-prefill with later turns unaffected; Qwen3-0.6B mean
954 ms/item (long bucket mean 1.99 s, worst 2.29 s), VmHWM ~1.0 GB; MiniCPM5-1B mean
2.9 s, worst 7.6 s, ~1.13 GB, and rambles past the 512-token cap on 10/40 items.

Decision-relevant reading: at 0.6–1B scale both candidates fail the restraint contract by
a wide margin (rewrites, deletions, synonym substitution, numeral conversion, identifier
case changes), while a ~20-line mechanical baseline delivers the punctuation benefit with
two protected-substring violations. Device latency/memory remains to be measured; text
quality measured on host carries over (same weights, quant, decoding) up to FP-kernel
differences.

## Device collection procedure (the reason this branch exists)

```
adb install --no-streaming -r <signed debug apk>
adb shell mkdir -p /sdcard/Android/data/org.fcitx.fcitx5.android.debug/files/postproc
adb push Qwen3-0.6B-Q4_K_M.gguf /sdcard/Android/data/org.fcitx.fcitx5.android.debug/files/postproc/
adb logcat -c && adb logcat -s LlamaPostProc   # load ms, per-item timings, VmHWM
```

Speak a Chinese sentence in any text field with the voice trigger; the accepted final is
held up to 4 s (`TextPostPolicy.TIMEOUT_MS`), then the processed or raw text is committed.
Removing the file (or `adb shell rm`) switches the stage off; swapping models requires an
app restart. Collected evidence should cover: cold load, warm latency per length band,
TTFO, PSS/RSS (`dumpsys meminfo`), coexistence with an installed XAsrOffline model, and
repeat-session stability.
