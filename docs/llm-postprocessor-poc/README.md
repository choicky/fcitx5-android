# Local Text Post-Processor — Gate A seam + Local-LLM evaluation record

Status: **the optional post-processing seam is retained (D017); the tested Local-LLM route is
stopped and its runtime removed from this repository.** No post-processing implementation is
currently configured or shipped: `TextPostProcessor = null`, so normal product behavior is
`ASR → Raw Transcript → IME` exactly as before.

## What is retained (Gate A)

A provider-neutral, optional `TextPostProcessor` boundary plus the minimal
`VoiceInputFlow` ownership mechanism for a pending asynchronous result:

- `input/voice/TextPostProcessor.kt` — the callback boundary and `TextPostPolicy.TIMEOUT_MS`;
- `VoiceInputFlow` — a flow-local `processingToken` armed when an accepted **final** reaches the
  flow and cleared by exactly the paths that invalidate a pending result (`begin`/`cancel`/
  `close`); `VoiceInputSession` is untouched and no second state machine exists;
- semantics, unit-proven in `VoiceInputFlowTest` (15 tests): owned success → processed text
  committed once; owned failure (exception/timeout/blank) → Raw Transcript committed once;
  lost ownership (cancel, input-target change, close, newer session, stale/late result) →
  nothing committed; processor null → previous behavior; only finals (never partials) enter
  the processor; D035 fallback unchanged.

The seam is deliberately not a provider framework, registry, plugin system or configuration
matrix. A future implementation (Cloud, self-hosted or local), if ever justified, must still
pass its own decision process: **the existence of the seam does not accept any implementation.**
Cloud LLM is not implemented in this repository.

## What was evaluated and stopped (Local-LLM)

Decision question: can a small local CPU LLM post-process Chinese ASR finals under a
conservative no-damage contract — only punctuation/spacing/indisputable fixes; protected
numbers/names/identifiers preserved verbatim; already-correct text returned byte-identical?

Frozen constraints (single prompt, no tuning campaign; recorded for reproducibility):
exactly one Chinese proofreading system prompt (982 bytes UTF-8, **no trailing newline**, the
rule-2 continuation line indented by three spaces, sha256
`9517338661dd4ac3ddc74b4e59a5549476a40f1ec51927ef635554b79534c79c`); single-turn; greedy
decoding (`temperature=0`); thinking mode off (`enable_thinking=false`).

Candidates (identities verified before use; details in the preflight evidence of PR #14):

| model | artifact | sha256 |
| --- | --- | --- |
| Qwen3-0.6B (official identity, community Q4_K_M quant) | `Qwen3-0.6B-Q4_K_M.gguf`, 484.2 MB | `cd47557a67d7e8f2891d98b5e1dbf2988544569fdf4f1bdb30e92b71aa61b548` |
| MiniCPM5-1B (official publisher GGUF Q4_K_M) | `MiniCPM5-1B-Q4_K_M.gguf`, 688.1 MB | `81b64d05a23b17b34c475f42b3e72fbde62d4b92cc34541f7a8031d0752deafa` |

Runtime pin: llama.cpp v0.6.0 (`d81235049384534c167caea52b85a694f6103d14`), CPU-only, mmap
load, greedy decode, deterministic (repeat runs byte-identical), abort callback honored
sub-millisecond — host (x86_64).

Quality result (deterministic character-diff oracle over a 40-item constructed corpus,
8 categories × short/medium/long; hard failure = any non-punctuation insert/delete/replace,
protected-substring loss, or non-identical already-correct item):

| run | items with hard failures | never reached EOS | already-correct byte-identical | useful punctuation |
| --- | --- | --- | --- | --- |
| baseline: no processing | 0/40 | 0/40 | 5/5 | 0/11 |
| baseline: mechanical rules | 2/40 | 0/40 | 5/5 | 11/11 |
| Qwen3-0.6B Q4_K_M | 19/40 | 0/40 | 1/5 | 1/11 |
| MiniCPM5-1B Q4_K_M | 28/40 | 10/40 | 1/5 | 2/11 |

Both models rewrite, delete clauses, substitute synonyms, convert Chinese numerals to digits,
change identifier case, and fail byte-identity on already-correct text — the failure modes the
conservative gate exists to catch — while a ~20-line mechanical baseline achieves the
punctuation benefit almost damage-free.

Conclusion: **the tested 0.6B–1B Local-LLM route fails the current conservative ASR
post-processing quality gate by a wide margin.** Real-device feasibility testing and
productization of this route were therefore stopped: text quality does not depend on the
target CPU, so further latency/memory/device work could not change this product decision.
No device experiment was run (none was needed for this conclusion); no prompt tuning, no
further models, no quantization/backend sweeps were attempted — that would be a different
decision process.

## Evidence strength and where the raw artifacts live

The raw artifacts (corpus builder, per-item model outputs, deterministic scorer, timing
summaries, adjudication list, the frozen prompt file, gate-A diff archive) were produced in a
task workspace **outside this repository** (`~/worktmp/fcitx5-android/local-text-postprocessor-poc/`
on the recording machine, 2026-10-13) and are not committed here. This file is a **summary**
with artifact identities (sha256 above), not the raw evidence. The numbers were not re-derived
in this repository.

## What this PR removed, and why the history is visible

This PR originally (commit `8c614c1f`) also contained a working Android arm64 CPU-only
llama.cpp packaging (submodule `app/src/main/cpp/llama.cpp`, JNI facade
`app/src/main/cpp/poc-postprocessor/`, a `LlamaPostProcessor` implementation wired into
`VoiceInputComponent`, and Gradle/CMake target wiring) which produced and signed a debug APK.
That runtime is removed in the closure commit so no build — Release or Debug — contains the
abandoned post-processor runtime; the two build/runtime defects review had identified in it
(the native runtime's reachability from an arm64-only Release build, and a pre-start
cancellation/supersede race in its single-flight runtime) are resolved by the removal of
those paths, not repaired. The removal does not erase the evaluation: the record above and
the PR history (and the superseded prerelease APK tag `test-local-postproc-8c614c1f`)
document what was built, measured and concluded.
