# Evidence — Android on-device LLM inference runtimes (official sources, 2026-10-13)

## 1. llama.cpp (ggml-org)
- MIT, actively released; latest tag reported **v0.6.0, 2026-10-05** — CONFIRMED
  (github.com/ggml-org/llama.cpp/releases). GGUF is the native format.
- Official Android build paths — CONFIRMED (docs/android.md): Android Studio project
  `examples/llama.android`; NDK cross-compile
  `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
  -DGGML_LLAMAFILE=OFF -DLLAMA_OPENSSL=OFF`; Termux.
- CPU: ARM hardware-feature autodetect up to **SME2** (AMX on x86); KleidiAI via
  `-DGGML_CPU_KLEIDIAI=ON`; arm82/dotprod kernels standard — CONFIRMED.
- Kotlin integration — CONFIRMED: docs describe an official binding (`GgufMetadataReader` taking a
  ContentResolver Uri or a private-storage File, `AiChat` facade → `InferenceEngine`, prompt →
  prefill → decode streamed as a Kotlin `Flow`). No packaged Maven SDK beyond the example; JNI
  layering is the app's own.
- OpenCL — CONFIRMED documented Android NDK build; upstream text says acceleration "through
  OpenCL on recent Adreno GPU"; Mali support described as weak/variable (docs/build.md).
- Vulkan — `GGML_VULKAN` exists and is no longer flagged experimental, but **no Android-specific
  official guidance**; whether mobile Adreno/Mali drivers perform — UNVERIFIED.
- NPU — a **Hexagon backend (`GGML_HEXAGON`, docs/backend/snapdragon)** exists, built with
  Qualcomm-provided Docker toolchains requiring the Hexagon SDK; existence CONFIRMED, phone
  maturity UNVERIFIED.
- mmap on Android storage — loading GGUF from shared storage fails (EINVAL),
  issue #18804 (closed) — CONFIRMED → the model must sit in app-private storage, which is exactly
  where `LocalModels` already installs (`noBackupFilesDir`).
- Cancellation — `llama_set_abort_callback()` exists; header comment: "currently works only with
  CPU execution" — CONFIRMED limitation, so GPU/NPU mid-decode abort is not available.
- Load time for 0.5–2 B Q4 on phones — no official numbers, UNVERIFIED (must be measured).

## 2. LiteRT-LM (Google AI Edge)
- "Production-ready orchestration layer to run LLMs with LiteRT" — Apache-2.0,
  **v0.18.0 released 2026-10-06**; still 0.x, `ExperimentalFlags` gate new features → expect
  breaking changes — CONFIRMED (github.com/google-ai-edge/LiteRT-LM; ai.google.dev/edge/litert-lm).
- APIs: Kotlin/Java artifacts `litertlm-android` / `litertlm-jvm`, plus C++/Python/Swift/JS/Flutter;
  Android docs cover CPU/GPU/**NPU** with vendor-specific native libs — CONFIRMED.
- Format `.litertlm`; `litert-lm import --from-huggingface-repo=…`; non-Google models including
  **Qwen explicitly listed** ("Gemma, Llama, Phi-4, Qwen, and more") — CONFIRMED (conversion needed).
- Third-party usable: yes (open source; Google Play "Google AI Edge Gallery"); powers Chrome /
  Pixel Watch / Gboard — CONFIRMED.
- NPU from an ordinary app — CONFIRMED as the only packaged route: LiteRT `CompiledModel` +
  vendor runtimes for **Google Tensor, Qualcomm QNN, MediaTek NeuroPilot, Intel OpenVINO,
  Samsung Exynos AI LiteCore**; named supported SoCs include Dimensity 7300/8300/9000/9200/**9300**
  /9400/9500; vendor runtime libs delivered via Maven / Play Feature Delivery
  (developers.google.com/edge/litert/next/mediatek, updated 2026-05-28).
- Google's 3×/7×-vs-llama.cpp speed claims are vendor marketing — not independent evidence.

## 3. Gemini Nano via AICore / ML Kit GenAI
- Runs inside the AICore system service; apps use ML Kit GenAI APIs: Prompt 1.0.0-beta4,
  Summarization beta1, Image description beta1, Speech recognition alpha1, Proofreading/Rewriting
  beta — CONFIRMED (developer.android.com/ai/gemini-nano; Google Maven probed 2026-10-13).
- Prompt API accepts custom prompts but **only Google's fixed Nano models; no custom weights, no
  backend choice** — CONFIRMED.
- Supported devices (developers.google.com/ml-kit/genai, fetched 2026-10-13): Pixel 9/10/11,
  Galaxy S25/S26/Z Fold7/8, **vivo X200/X300**, OPPO Find N5/X8/X9, Xiaomi 14T Pro/15/17; API 31+.
  → **vivo X100 Pro not listed; Redmi K90 Pro Max not listed; 国行 firmware lacks AICore.**
- Gemini Nano 4 in AICore Developer Preview (announced 2026-05-26, I/O 2026 blog), GA "later this
  year"; Structured Output and Prefix Caching upcoming — CONFIRMED.
- `com.google.android.gms.ai` as a separate API surface — UNVERIFIED (docs consistently point at
  ML Kit GenAI + AICore).

## 4. NPU reality check
- **NNAPI deprecated in Android 15** — CONFIRMED (official migration guide): "most devices are
  expected to use the CPU backend"; recommended replacements LiteRT/TFLite-in-Play-Services and
  AICore; NNAPI remains callable but is not a forward path.
- No new system-wide accelerator API replaced it; Google's answer is the LiteRT vendor-delegate
  path above. Vendor SDKs (Qualcomm QNN/Genie — account-gated download; MediaTek NeuroPilot 8.x +
  Dimensity GenAI ModelHub) exist and are CONFIRMED third-party-accessible with vendor
  registration; how much works from a sideloaded APK without vendor libs present is UNVERIFIED.
- Bottom line: a sideloaded APK realistically runs LLMs on **CPU/GPU**; NPU execution requires
  shipping the matching vendor runtime, currently best packaged through LiteRT-LM's documented NPU flow.

## 5. Other runtimes
- **MLC-LLM** — Apache-2.0, active (pushed 2026-10-06), Android via Vulkan, requires
  compile-per-model — CONFIRMED.
- **ExecuTorch** — BSD-3, Meta, very active (pushed 2026-10-13), Android + QNN delegates; LLM
  support real but integration-heavy — CONFIRMED (repo metadata).
- **ONNX Runtime GenAI** — MIT, active; official Android/mobile LLM parity UNVERIFIED.
- **Nexa GenieX** — `github.com/NexaAI/nexa-sdk` now redirects to `qualcomm/GenieX` (BSD-3,
  active, LLM/VLM on Qualcomm NPU/GPU/CPU); repo transfer CONFIRMED, acquisition details UNVERIFIED.

## Fusion-relevant conclusion
llama.cpp (MIT, GGUF, CPU-first, app-private mmap, documented Kotlin/JNI shape, CPU-only abort
callback) is the smallest credible first backend; LiteRT-LM (Apache-2.0, 0.x) is the right vehicle
if and only if the MediaTek/Qualcomm NPU question is actually opened; ML Kit GenAI is unavailable
on both target devices. Sources: docs/android.md, docs/build.md, LiteRT-LM repo + ai.google.dev
Android page, developers.google.com/edge/litert/next/mediatek, developer.android.com/ai/gemini-nano,
developers.google.com/ml-kit/genai, NDK NNAPI migration guide, ggml issue #18804.
