# Evidence — target device SoC / runtime facts (collected 2026-10-13)

Labels: CONFIRMED / LIKELY / UNVERIFIED. Primary OEM spec pages were partly unreachable; where
only Chinese secondary tech media exist, the label reflects that.

## vivo X100 Pro (国行)
- SoC MediaTek Dimensity 9300 (MT6989), TSMC 4nm 3rd gen, "蓝晶×天玑9300" — CONFIRMED
  (vivo.com/hk X100 Pro product page; mediatek.com/dimensity-9300)
- CPU 4× Cortex-X4 @3.25 GHz + 4× Cortex-A720 @2.0 GHz; GPU Immortalis-G720 12-core — CONFIRMED
- ISA Armv9.2; dotprod/i8mm/bf16 on X4/A720; no SME/SME2 — LIKELY (per-core not independently
  confirmed)
- NPU APU 790 (7th gen) with GenAI hardware engine; MediaTek claims 1B–33B LLM support and
  "30 TOPS" — existence CONFIRMED, TOPS = vendor marketing, NOT measured performance
  (developer.mediatek.com/ai)
- SDK MediaTek NeuroPilot 8.0 lists Dimensity 9300 support; Google LiteRT NeuroPilot accelerator
  covers D7300–D9400 (announced 2025-12) — CONFIRMED / LIKELY
- 12 GB / 16 GB LPDDR5T; shipped OriginOS 4 on Android 14 — CONFIRMED
- ML Kit GenAI / Gemini Nano: **X100 Pro is NOT on the official supported-device list**
  (list contains X200/X200 Pro/X300/X Fold3 Pro/X Fold5); 国行 lacks Google Play services/AICore
  — CONFIRMED (developers.google.com/ml-kit/genai, fetched 2026-03 and 2026-10-13)
- Vendor on-device LLM 蓝心 BlueLM-Nano 3B announced (VDD 2026-09-16); system 蓝心小V only, no
  public third-party on-device runtime — CONFIRMED (announcement) / LIKELY (system-only)
- Thermal: no credible X100-Pro-specific sustained-clock data — UNVERIFIED (only aggregate
  D9300-vs-SD8Gen3 secondary reporting)

## Redmi K90 Pro Max (国行, launched 2025-10-23)
- SoC Snapdragon 8 Elite Gen 5 (SM8850), 3nm — CONFIRMED
- CPU 2× Oryon V3 "Phoenix L" @4.6 GHz + 6× Oryon V3 "Phoenix M" @3.62 GHz; Adreno 840 — CONFIRMED;
  dotprod/i8mm present; bf16/SME on Oryon V3 — UNVERIFIED
- NPU Hexagon; Qualcomm QNN targets it as HTP v81 — CONFIRMED (HTP v81). "100 TOPS / 220 tok/s"
  claims are Qualcomm marketing — NOT performance evidence
- SDK Qualcomm AI Engine Direct (QNN/QAIRT) + Genie, CPU/GPU/NPU backends — CONFIRMED
  (docs.ultralytics.com/integrations/qnn; account-gated vendor download)
- 12/16 GB LPDDR5X, 256 GB–1 TB UFS 4.1; shipped Android 16 / HyperOS 3 — CONFIRMED/LIKELY
- ML Kit GenAI: **not on the supported list** (Xiaomi 14T/15/16/17 series only, no Redmi K90);
  国行 lacks GMS/AICore — CONFIRMED
- Vendor LLM 超级小爱 2.0 (Xiaomi MiMo), "端侧隐私 AI" — LIKELY; third-party exposure UNVERIFIED
- Thermal: passive-cooled; user reports ~45 °C and 3–5 fps fluctuation after >30 min continuous
  gaming; vendor claims 40.5 °C @2h Genshin — LIKELY / marketing

## Realistic inference paths for a sideloaded Fusion APK
| Device | CPU | GPU | NPU | Google GenAI |
|---|---|---|---|---|
| X100 Pro | CONFIRMED feasible (llama.cpp arm64, dotprod LIKELY) | Vulkan on Immortalis-G720 — LIKELY | NeuroPilot/LiteRT exists for D9300, but real access from a third-party APK on vivo firmware — UNVERIFIED | not available (CONFIRMED) |
| K90 Pro Max | CONFIRMED feasible | OpenCL/Vulkan Adreno + QNN GPU — CONFIRMED SDK / LIKELY device | QNN HTP v81 SDK-supported in principle; fastrpc availability on 国行 HyperOS — UNVERIFIED | not available (CONFIRMED) |

Conclusion used by preflight.md: on both targets the only *verified* execution tier today is CPU;
12 GB RAM units can hold a small Q4 model, but neither device offers the strict Google path.
