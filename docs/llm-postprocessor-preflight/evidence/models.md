# Evidence — candidate small models for Chinese ASR text post-processing (2026-10-13)

## Kept

1. **Qwen3-0.6B** (text-only, released 2025-04) — 0.6 B params, Apache-2.0 (CONFIRMED:
   Qwen3 model cards / Baidu baike card 2026-08; all dense Qwen3 sizes Apache-2.0). Official
   release is FP8; GGUF quantisations are community (unsloth, bartowski, empero-ai). Q4_K_M
   ≈0.4 GB download, ≈0.6–0.8 GB resident with short context (arithmetic estimate, LIKELY).
   Runs in llama.cpp/Ollama. Experiment question: is 0.6 B enough for conservative
   punctuation + zh/en spacing + restrained correction at minimal footprint?
2. **Qwen3.5-0.8B / 2B** (2026-02) — CONFIRMED from fetched HF cards `Qwen/Qwen3.5-0.8B`,
   `Qwen/Qwen3.5-2B`: Apache-2.0, 0.8 B, 262 K context, **natively multimodal**
   (image-text-to-text), 331+ community quantisations; GGUF runs in llama.cpp (field reports,
   LIKELY). Caveats: vision encoder inflates the file unless a text-only conversion is used;
   newer hybrid architecture needs a current llama.cpp. Third-party note (CSDN 2026-03, LIKELY)
   that quantisation degrades small-model correction quality. Experiment question: does the
   newer generation's instruction-following justify the extra footprint/complexity?
3. **MiniCPM5-1B / 2B** (OpenBMB) — CONFIRMED from fetched card `openbmb/MiniCPM5-1B`:
   Apache-2.0, **official GGUF repos** (`openbmb/MiniCPM5-1B-GGUF`, `-2B-GGUF`), text-only
   Llama-style arch, 131 K context, think/no-think modes, zh+en, 1.08 B total / 0.68 B
   non-embedding params. 1B ~2026-08; 2B open-sourced 2026-09-07 (third-party, LIKELY).
   Q4 ≈0.7 GB (1B) / ≈1.3 GB (2B), LIKELY. Also `OpenBMB/MobileCPM` Android toolkit (CONFIRMED repo).
   Experiment question: is a purpose-built 2026 on-device model the best quality/size point?
4. **Non-LLM control: FunASR CT-Transformer punctuation**
   `iic/punc_ct-transformer_cn-en-common-vocab471067-large` — Apache-2.0, zh+en, official
   quantised ONNX `model_quant.onnx` (~0.9–1.1 GB), smaller vocab272727 variants; runs on ONNX
   Runtime; deployed on Android via sherpa-onnx and FunASR runtime (LIKELY, juejin 2026-10).
   Punctuation/segmentation only, no error correction. Optionally paired with
   **MacBERT-4CSC** `shibing624/macbert4csc-base-chinese` (CONFIRMED card: Apache-2.0, ~0.1 B,
   ~400 MB fp32, ONNX-supported, SIGHAN2015-class F1) for homophone correction.

## Dropped
- **Gemma 3 1B / Gemma 4 2B** — custom Google license terms (flow-down obligations, unilateral
  modification; CONFIRMED license type via wcr.legal 2026-03 analysis), plus Chinese reputedly
  weaker than Qwen/MiniCPM at this size (UNVERIFIED).
- **LFM2-1.2B** (Liquid AI) — "LFM Open License v1.0" gates commercial use on company revenue
  (<$10M) — CONFIRMED from HF README; Chinese is one of 8 languages, depth unproven.
- **Qwen2.5-0.5B** — superseded by Qwen3-0.6B at similar Q4 footprint; keep only as a
  lowest-memory fallback (LIKELY).
- **SmolLM2 / Phi-mini / InternLM2.5-1.8B / MobileLLM** — weak Chinese or non-permissive /
  research licenses (dropped without deep verification, UNVERIFIED).

## Notes carried into preflight.md
- All kept LLM weights are Apache-2.0, so an APK downloading from a pinned HF/ModelScope upstream
  needs license/attribution records (matching the project's existing D059 four-rights pattern),
  and community GGUFs inherit Apache-2.0 (LIKELY).
- The benchmark must load the LLM **together with** the resident Local ASR recognizer: shared CPU
  and shared RAM; standalone LLM numbers would not represent Fusion.
