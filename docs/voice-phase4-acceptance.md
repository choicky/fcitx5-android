# Phase 4 voice settings acceptance

This document records the historical acceptance scope for the `phase4-voice-poc` branch.
The current Local model set and recommendation behavior are defined by the Phase 5 D045
checkpoint in the control repository.

## Historical PASS records

Existing A testing records and conclusions remain historical records. This change retires A from
the user-facing catalog; it does not rewrite those records as if A had never existed. No existing
acceptance record was present in this checkout to edit.

## This change

- Historical A/B/C acceptance covered A (`ZipformerZh`), B (`FunAsrNano`), and C
  (`ZipformerBilingual`). The current implementation removes A from the supported set; no
  external-user migration is required. A-specific preferences are no longer parsed as a current
  model, while shared Zipformer runtime code remains for C.
- The historical B/C order and persistence statements above apply to that acceptance version.
  The current user-facing set is FunASR Nano plus X-ASR offline and X-ASR streaming; the
  retired bilingual model is removed without compatibility migration.
- The system group is named “系统自带 ASR”. Its one-row enablement, unavailable, authorization,
  and disclosure behavior remains covered separately.
- Doubao, Qwen, and Tencent switch titles use short service names. Credential storage, redaction,
  availability checks, service selection, and recognition paths are unchanged.
- The self-hosted list uses a short explanation; protocol and model choices remain available in
  the add/edit form and its privacy disclosure.

## Automated checks

Run when a JDK and Android SDK are available:

```text
./gradlew :app:testDebugUnitTest --tests 'org.fcitx.fcitx5.android.input.voice.*' --tests 'org.fcitx.fcitx5.android.data.prefs.VoicePrefsTest'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.ui.main.settings.behavior.VoiceSettingsLayoutTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.ui.main.settings.behavior.VoiceSettingsProgressTest
BUILD_ABI=arm64-v8a ./gradlew :app:assembleDebug
```

The historical tests covered A non-selection/non-start and old A current state. Current tests
cover A removal, B/C ordering, System ASR authorization states, cloud enablement and credential
availability behavior. APK compilation is not a real-device PASS.

## Current Local ASR recommendation acceptance — historical PASS

The project owner completed physical-device acceptance for the current Local ASR cleanup and
recommendation batch using Android implementation HEAD `c916d3e144d5e057936723f3e221930409dc2229`.
GitHub Actions run `36802256868` passed; the tested APK was artifact `11136369038`, package
`org.fcitx.fcitx5.android.debug`, signed with the fixed certificate SHA-256
`41:70:5B:C9:4F:42:26:FF:FA:E9:60:91:B7:BA:36:F2:C0:55:B6:2A:93:DF:4E:B9:58:9C:A9:96:A3:7C:7A:7E`.

The device acceptance PASS covers:

- only FunASR Nano and bilingual Zipformer remain supported; Chinese-only Zipformer A is absent;
- with both retained models installed and enabled and `current == null`, One-click recommendation
  selects FunASR Nano;
- when FunASR Nano is unusable and bilingual Zipformer remains usable, recommendation selects
  bilingual Zipformer;
- with no usable Local model, existing Android System ASR recommendation/disclosure behavior
  remains;
- successful recommendation persists a concrete current provider and hides the recommendation;
- clearing current back to null makes the recommendation available again even after prior use;
- an explicitly selected provider becoming unavailable is not silently replaced;
- microphone voice flow, long-press Space, Stop/Cancel, restart/persistence and relevant voice
  regressions pass.

This is a device PASS for retained Local model/recommendation behavior. It does not change the
separate public-distribution status: FunASR Nano and bilingual Zipformer remain research/private
models with public in-product distribution pending their documented license/provenance review.

## Current three-model owner report (2026-10-02)

The owner reports having used both X-ASR models and subjectively finding their recognition better
than the retired bilingual Zipformer. The owner also reports that the current debug Voice Settings
made Nano and both X-ASR models convenient to download. This is recorded as owner-reported basic
device use and subjective comparison only. Device names, APK/commit, exact cases, and quantitative
measurements were not provided. It does not establish PASS for offline operation, pause/resume/
cancel/continuation, checksum failure, long utterances, latency/memory, cancellation/duplicate
submission, or the new D035 order. The current implementation target is Nano plus the two X-ASR
models; the bilingual model is removed without compatibility migration. Release eligibility is
governed by the current Change Contract and CI evidence, not by this report.

### Three-model release evidence (2026-10-02)

Debug CI run `36980804642` passed the debug APK, release Kotlin compilation,
and instrumented-test compilation steps (instrumented tests were not run
because CI has no device). Formal Release `v0.1.3-moqi.4` was then built and
published by run `36981898289`; its arm64-v8a APK SHA-256 is
`fd35e5627a36ec23a342534969cc5c93bbb7bed55633cab9fc60fae39a623e88`.
The release was intentionally not gated on new real-device acceptance.

## Remaining historical/other voice checks

- Verify System ASR “未授权” and “已停用” remain distinct, including disclosure and revoke flows;
  confirm the possible network disclosure is visible.
- Verify Doubao/Qwen/Tencent titles, switches, encrypted credential editing, hidden secrets, and
  missing/incomplete credential states on a device.
- Verify self-hosted protocol/model choices and recipient/privacy text in add/edit screens.
- B/C performance comparison is intentionally deferred and is not required for this change.
