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
- The current user-facing order is B (`FunAsrNano`) followed by C (`ZipformerBilingual`). B/C
  IDs, paths, checksums, installed state, enablement, and current selection remain persisted.
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

## Pending real-device checks

- Verify the current Local list contains only B and C, with no A row, switch, download, import, or
  alternate-source action; no A migration is required for current test devices.
- Verify FunASR Nano precedes bilingual Zipformer and that current-null recommendation selects the
  first usable retained Local model.
- Verify System ASR “未授权” and “已停用” remain distinct, including disclosure and revoke flows;
  confirm the possible network disclosure is visible.
- Verify Doubao/Qwen/Tencent titles, switches, encrypted credential editing, hidden secrets, and
  missing/incomplete credential states on a device.
- Verify self-hosted protocol/model choices and recipient/privacy text in add/edit screens.
- B/C performance comparison is intentionally deferred and is not required for this change.
