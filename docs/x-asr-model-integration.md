# X-ASR manual model comparison (2026-10-02)

## Change Contract

Goal: add the exact X-ASR offline INT8 and 960 ms streaming INT8 artifacts to the
existing Local model manager and manual service selection. Allowed scope: pinned
model metadata, file verification, necessary recognizer adapters, two labels,
focused tests and progress/license records. Preserve Provider/VoiceInputFlow,
existing model recommendation/fallback behavior, installation/download/pause/
resume/cancel/SHA/atomic replacement, Mic/Space/Stop/Cancel, current capture limit,
and all Toolbar/Dictionary behavior. Preserve every existing Voice Settings
section, order, title, component, style and interaction. Expected delta: two new
manual Local choices; neither is recommended or a D035 target.

## Current implementation boundary

Both models are **import-only** in this batch (`downloadBase=null`), using the
existing multiple-file import, SHA verification, atomic install, enable/use,
delete and details controls. Download the fixed archive separately, extract it,
then pick its four recognition files together in the existing Import action.
No archive parser/downloader, hotwords, new Provider, section or page was added.
Existing model rows retain their order; X-ASR rows follow them. Both new entries
have `recommendationEligible=false` and `production=false`. Here production only
means automatic-fallback eligibility has not been approved; it does not decide
manual usability, licensing or build capability. Existing model qualifications,
defaults and download paths are unchanged.

The current project has sherpa-onnx 1.13.8 in **debug only**; release LocalAsrEngines
is still an unavailable stub. This batch does not add release runtime or publish
an APK. A release build may manage/import the files but cannot recognize locally.

## Exact artifacts and file verification

Registry snapshot: xifan2333/vinput-registry
`da1010e99e3e3b79fcbd41bf9c267e1e68f80881`, registry/models.json.
fcitx5-vinput implementation snapshot:
`cd59da9d82f0cb4c9a9f789ff044c956174a8b0d`.
Both GitHub archives were actually downloaded and size/SHA-256 checked on
2026-10-02. Do not replace these hashes with a newer same-name asset. The shared
asr-models tag and filename are mutable; identity is pinned by size/hash/asset ID.


### Offline INT8

Registry ID: `model.sherpa-onnx.x-asr-zipformer-transducer-zh-en-punct-int8`. Asset ID: `460927314`. Archive: 136396739 bytes;
SHA-256 `5d02c36d7b44e886b7c8f0d8e051f8713acab96c264bb6ef9e718be39a6a2224`.

[Fixed archive URL](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03.tar.bz2).

All entries are under `sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03/`. Recognition files (catalog stores these actual extracted sizes/hashes):

| File | Bytes | SHA-256 |
|---|---:|---|
| encoder-epoch-99-avg-1.int8.onnx | 161015713 | 7f6aa62056efd8af9da13e0faa81cd3f284d2fb2e3b63de56fd2dfd3450910dc |
| joiner-epoch-99-avg-1.int8.onnx | 2581422 | aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3 |
| tokens.txt | 58806 | b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c |
| decoder-epoch-99-avg-1.onnx | 11309084 | 72f47405d3c1033bebccbef82f90071e7b4ba3e71b9c986f2b74244b25723aed |

### 960 ms streaming INT8

Registry ID: `model.sherpa-onnx.x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8`. Asset ID: `460927089`. Archive: 133895831 bytes;
SHA-256 `0a92b798bd6801c333c7ce8aebf5ba769bfe7f3f3511699a67837b2288428603`.

[Fixed archive URL](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2).

All entries are under `sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05/`. Recognition files (catalog stores these actual extracted sizes/hashes):

| File | Bytes | SHA-256 |
|---|---:|---|
| decoder.onnx | 11309084 | a1cbc9eac2d5e3fb6617a218c67ad6daaa7f4e0fd225f08b2c22ab0413c8c257 |
| encoder.int8.onnx | 155276576 | 017e3cf23097302dbc57ebd72cf4a209cf55c367920669e2d9ce9c0381a96ddd |
| tokens.txt | 58806 | b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c |
| joiner.int8.onnx | 2581422 | aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3 |

Each archive also contains bpe.model, README.md, test_onnx.py and test_wavs/0–3.wav.
Neither archive contains LICENSE or NOTICE. bpe.model is a SentencePiece model
used by vinput's optional hotword vocabulary preparation; the actual packaged
test_onnx.py scripts both decode using tokens.txt without reading bpe.model.
The project's recognizer config also uses tokens.txt without hotwords and
**does not need bpe.model or generated bpe.vocab**. Only the four recognition files are installed.
No model weights, test audio or exporter script is committed or bundled in APK.
The previously researched offline HF mirror is **not the same binary**: its
encoder/decoder SHA-256 differ from this verified archive. It is not a substituted
download source. No matching fixed per-file streaming source was established.

## Licenses and download gate

- Original model card: GilgameshWind/X-ASR-zh-en revision
  `689ff18c584d29910da37b6fe904db0c1489c9d1`, metadata and License section
  declare Apache-2.0; card links Gilgamesh-J/X-ASR.
- Original source: Gilgamesh-J/X-ASR
  `838297cd47fed858e6cf72eaf5a52f948a3edd73`, root LICENSE and README declare
  Apache-2.0. The original LICENSE is preserved in
  [licenses/X-ASR-Apache-2.0.txt](licenses/X-ASR-Apache-2.0.txt).
- Export source: sherpa-onnx
  `040afe360a38e25daaa325ce8889abf93ea02609`,
  scripts/zipformer-transducer/x-asr/{export-non-streaming,export-streaming}.sh
  and .github/workflows/export-x-asr.yaml. Root LICENSE is Apache-2.0;
  bundled test script attributes Copyright 2026 Xiaomi Corp., author Fangjun Kuang.
  Scripts use the author's punctuation-finetuned checkpoint and tokens through
  mutable main URLs, then icefall ONNX exporters. Icefall's current root LICENSE
  at `3f848bb6d0acc970c9b294a30ca0a04a7c9c78d1` was separately checked as Apache-2.0;
  the historical exporter revision is not pinned.
- Actual package README links the author; it supplies no License/Notice text.
  Missing package notices and precise historical export/checkpoint mapping remain
  distribution-audit gaps. Author declaration is evidence for private testing,
  **not a claim that every archive redistribution obligation is closed**.
- Before offering a public in-product download, record exact weight/export
  attribution and applicable original notices, retain Apache license and any
  required modification notice, establish a fixed byte-matching source compatible
  with the existing installer, and decide distribution approval separately.
  Archive sample audio/export test script licensing would need separate review if
  redistributed; neither is used by the app. No weights are hosted or mirrored by us.

[Author card](https://huggingface.co/GilgameshWind/X-ASR-zh-en/blob/689ff18c584d29910da37b6fe904db0c1489c9d1/README.md),
[Author license](https://github.com/Gilgamesh-J/X-ASR/blob/838297cd47fed858e6cf72eaf5a52f948a3edd73/LICENSE),
[Export workflow](https://github.com/k2-fsa/sherpa-onnx/blob/040afe360a38e25daaa325ce8889abf93ea02609/.github/workflows/export-x-asr.yaml).

## Recognition configuration and evidence

Offline: OfflineRecognizer + OfflineTransducerModelConfig, INT8 encoder/joiner,
FP32 decoder, tokens.txt, CPU, 16 kHz/80-dim defaults, modified_beam_search
(default maxActivePaths=4), modelType empty for metadata detection. Streaming:
OnlineRecognizer + OnlineTransducerModelConfig, greedy_search, same features,
endpoint detection disabled (existing user Stop behavior). No hotwords/BPE vocab
or LLM/transcript postprocessing is configured.

Only X-ASR streaming adds 15,360 zero samples (960 ms) before inputFinished(),
then decodes all ready frames for one final result. Older streaming models still
use zero extra padding. In real 1.13.8 inference, inputFinished alone produced
2/6/4 fewer characters than padded finals on archive samples 0/1/2; sample 3
matched. For all four samples, 960 ms padded final equals 1920 ms padded final
and the trailing-pause control. New X-ASR partial logs contain only count/length,
never transcript content; partials still do not reach preedit. Final logging
remains existing aggregate metrics. Audio/transcripts are not newly persisted.

Both exact packages **loaded successfully** in sherpa-onnx 1.13.8 Linux aarch64
Python native runtime. Four 4.69–10.05s archive clips per model returned nonempty
finals; streaming outputs contain Chinese and Latin characters. A 21.60625s
in-memory mixed/pause clip returned nonempty output in both models; streaming
960/1920ms padding results matched. This is native-runtime interoperability,
not Android AAR/device verification or a CER/WER/quality PASS. Test harness wrote
only numeric/boolean results; no combined audio or transcript text was saved.

Focused JVM checks cover manual selection, persistence identity parsing,
recommendation exclusion, fallback exclusion, runtime/file/enable prerequisites,
existing actions, padding/final order, cancel-without-finalize, installation,
pause/resume/cancel and preservation of old models. Optional XAsrImportInteropTest
(set X_ASR_FIXTURES to the extracted archive parent) imports the real pinned
files and rejects altered tokens while preserving the verified installation.
Existing exact catalog-list expectations were expanded for the requested new
entries; old model order and download assertions remain, with additional
assertions for the import-only gate. No failing test expectation was weakened.

Local Gradle Android build cannot configure because no Android SDK is installed.
Debug loader compilation against the real pinned 1.13.8 AAR classes passed
(with isolated Android type stubs); this is a compile-risk check, not an APK build
or Android native loading claim. CI and device state are recorded in the batch
report/ROADMAP; never infer device PASS from compilation.

## Physical-device comparison checklist (pending)

1. On vivo/Redmi, install this branch's debug APK. Keep existing data and models.
   In the existing Local section import each extracted package's four files;
   verify details/size/attribution, enable then Use/current-service display.
2. Verify X-ASR is excluded from One-click recommendation and D035 fallback;
   existing Nano/bilingual order and current selection stay intact. Do not rely on
   a release APK: its Local runtime remains unavailable.
3. Use the same short Chinese and mixed-language phrases with a final pause;
   compare omissions, substitutions, punctuation and sentence ending. Record
   results voluntarily outside the app; do not enable full transcript logging.
4. Measure first recognizer partial latency through metrics (no new partial UI),
   stop-to-final latency, peak Android PSS/memory, repeated cold/warm sessions.
5. Mic click→click Stop; Space hold→release; slide-up→cancel; cancel while loading
   and finalizing; switch input field/hide keyboard. Cancel must commit nothing;
   late/duplicate callbacks must not submit text, normal final submits once.
6. Repeat start/stop 10 times, alternate both new and both existing models;
   inspect resource release/recovery and existing Mic/Space behavior.
7. Tampered or missing recognition files must not install; valid import and delete
   use existing actions. Existing downloads must retain progress/pause/resume/cancel.
   Confirm no section/style/button/layout changes beyond the two added rows.

Keep current approximately 60s capture cutoff. Do not test or implement 90/120s,
5-minute accumulation, Nano segmentation or longer recording in this batch.
Those are separate future decisions; strict full-session 60s is not claimed.
