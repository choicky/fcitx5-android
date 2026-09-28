# Phase 4 voice settings acceptance

This document records the current acceptance scope for the `phase4-voice-poc` branch.

## Historical PASS records

Existing A testing records and conclusions remain historical records. This change retires A from
the user-facing catalog; it does not rewrite those records as if A had never existed. No existing
acceptance record was present in this checkout to edit.

## This change

- A (`ZipformerZh`) is retained as a legacy model identifier and storage path so old preferences
  and files remain readable. It is absent from settings rows, enable controls, model actions,
  current-service candidates, and the local runtime status. An old current/enabled A selection is
  kept in preferences, resolves to unavailable with a prompt to choose another service, and never
  creates a backend. A files are not deleted.
- C (`ZipformerBilingual`) precedes B (`FunAsrNano`) in the user-facing catalog. B/C IDs, paths,
  checksums, installed state, enablement, and current selection remain persisted as before.
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
./gradlew :app:connectedDebugAndroidTest --tests 'org.fcitx.fcitx5.android.ui.main.settings.behavior.VoiceSettingsLayoutTest' --tests 'org.fcitx.fcitx5.android.ui.main.settings.behavior.VoiceSettingsProgressTest'
./gradlew :app:assembleArm64Debug
```

The tests cover A non-selection/non-start, old A current state, C/B ordering, System ASR
authorization states, cloud enablement and credential availability behavior. APK compilation is
not a real-device PASS.

## Pending real-device checks

- Upgrade an install with A enabled and current; verify the current row explains that A is no
  longer offered, the voice trigger does not start, the current-service chooser excludes A, and
  B/C can be selected without an automatic choice.
- Verify an A model directory remains after upgrade and B/C directories and installed states are
  unchanged.
- Verify C appears before B, with no A row, switch, download, import, or alternate-source action.
- Verify System ASR “未授权” and “已停用” remain distinct, including disclosure and revoke flows;
  confirm the possible network disclosure is visible.
- Verify Doubao/Qwen/Tencent titles, switches, encrypted credential editing, hidden secrets, and
  missing/incomplete credential states on a device.
- Verify self-hosted protocol/model choices and recipient/privacy text in add/edit screens.
- B/C performance comparison is intentionally deferred and is not required for this change.
