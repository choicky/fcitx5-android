# Evidence — reusable Fusion infrastructure facts (source-read, HEAD 11a7b4f0)

All paths relative to `app/src/main/java/org/fcitx/fcitx5/android/`.

## 1. AppPrefs mechanism (`data/prefs/AppPrefs.kt`)
- Two declaration styles. Non-UI backing flags live in `inner class Internal`
  (`AppPrefs.kt:32-83`) via `bool/string/int` helpers (`ManagedPreferenceInternal.kt:12-29`).
  User-visible toggles live in a `ManagedPreferenceCategory` (`AppPrefs.kt:86-97`, class `Voice`)
  via `switch/enumList/int/voiceInputPreference` (`ManagedPreferenceCategory.kt:16-146`), each also
  registering a `ManagedPreferenceUi`.
- Voice prefs: category `voice` (`AppPrefs.kt:86-97`, registered `:448`) holds only
  `systemAsrAllowed`; all real voice state is `internal.voice*` strings/bools (`AppPrefs.kt:40-70`).
  Registration is `val voice = Voice().register()` (`:448`). Adding a pref = one
  `bool("k", default)` / `string("k", default)` / `switch(title, R.string.key, default, summary)`;
  enums need `ManagedPreferenceEnum` (`ManagedPreferenceCategory.kt:52-65`).

## 2. `input/voice/VoiceSelectionStore.kt`
- Thin wrapper over SharedPreferences; no own file. `load/save` (`:28-58`) persist current service,
  enabled set, recommendation flag as comma-joined keys.
- Self-hosted instances persist as JSON inside one pref (`:77-79` → `SelfHostedInstance.encode/decode`).
- `lastError` is **not** a pref: `LastErrorRecord` file (`:20`, `:114-123`) at
  `noBackupFilesDir/voice/last-error` (`:158`), format `"<service>\n<redacted detail>"`, written
  atomically via `FileReplace` (`ErrorRedaction.kt:66-84`).
- No versioned schema: ad-hoc one-shot migration flags `voiceSelectionMigrated` /
  `voiceLocalModelsMigrated` (`:133-155`).

## 3. Credential storage
- `KeystoreSecretCipher : SecretCipher` — AES-256-GCM under a non-exportable Keystore key
  (`KeystoreSecretCipher.kt:22-58`); `store(context)` → `CredentialStore(noBackupFilesDir/asr-credentials, this)`
  (`:60-62`).
- `CredentialStore` API — `read(provider): Map<String,String>?`, `write(provider, Map)`,
  `has(provider)`, `clear(provider)` (`AsrCredentials.kt:39-64`); **one file per opaque provider
  string** (`<provider>.bin`), the provider name is bound as GCM AAD; blob format versioned with a
  `VERSION = 1` byte prefix (`:67`, `:84`).
- The abstraction is **provider-agnostic** (String id + Map). ASR-specificity exists only in the
  `fromStore` mappers/field constants: `DoubaoCredentials.PROVIDER = "doubao"` +
  `API_KEY/APP_KEY/ACCESS_KEY/RESOURCE_ID` (`DoubaoAsrBackend.kt:44-60`);
  `QwenAsrConfig.PROVIDER = "qwen"` + `API_KEY/WORKSPACE_ID/REGION/MODEL` (`QwenAsrClient.kt:44-60`);
  `TencentAsrConfig.PROVIDER = "tencent"` + `APP_ID/SECRET_ID/SECRET_KEY/ENGINE`
  (`TencentAsrClient.kt:36-55`); self-hosted token key `"selfhosted-$id"` with field `TOKEN = "token"`
  (`SelfHosted.kt:58,61`). A text-processor credential record reuses this as-is with a new provider string.

## 4. Self-hosted endpoint config (`input/voice/SelfHosted.kt`)
- `SelfHostedInstance(id, name, protocol, url, model)` with JSON `encode/decode`,
  `idPattern = [a-z0-9]{1,32}` (`:47-95`).
- `SelfHostedProtocol` enum carries `key` + `secureScheme` (`wss`/`https`, plain `ws`/`http`)
  (`:22-44`); `endpointProblem(url, allowCleartext, protocol)` validates scheme/host and rejects
  cleartext unless allowed (`:105-117`).
- Shape is directly reusable for an LLM endpoint (new protocol entry, or reuse of the
  OpenAiCompatible `https` rule) — but as its own instance list, not as an ASR instance.

## 5. Model download / install lifecycle
- `ModelCatalogEntry` (`ModelTasks.kt:25-149`): `model: LocalAsrModel`, `version`,
  `files: List<ModelFile(path, size, sha256)>`, `downloadBase` (pinned revision URL),
  `archiveUrl/archiveSize/archiveSha256` (tar.bz2), license metadata, `totalBytes`
  (`LocalModelInstaller.kt:54-55`), `downloadOffered(testBuild)` (`:58`).
- `ModelJobs.download/import/cancel/pause/resume` (`LocalModels.kt:89-120`);
  one job per model, process-lifetime scope, main-thread listeners (`LocalModels.kt:55-61`).
- `LocalModelInstaller`: staging dir → per-file SHA-256 verify → atomic `swapIn` rename
  (`:300-436`); archive extraction with path/size guards (`:212-284`); `remove` atomic (`:444-464`);
  resume of partial downloads (`:367`).
- Root: `LocalModels.root(context) = noBackupFilesDir/local-asr` (`LocalModels.kt:30`).
- **Coupling**: catalog, installer, tasks and jobs are all keyed by the `LocalAsrModel` enum
  (`LocalAsr.kt:15-72` `dirName/streaming/requiredFiles`), so an LLM model set needs either a new
  enum + parallel catalog or a genericized `ManagedModel` id.

## 6. Settings UI
- `ui/main/settings/behavior/VoiceSettingsFragment.kt` (1161 lines) extends
  `PaddingPreferenceFragment` and builds an AndroidX `PreferenceScreen` **programmatically** in
  `render()` (`:110`, `:220-225`), full rebuild on status change (`renderCount/renderedStatus`
  `:196-200`). There is no XML preference screen to extend.
- Row decisions come from pure logic files: `input/voice/VoiceSettingsRows.kt`
  (`systemRow/cloudStatus/selectableServices`) and `input/voice/ModelRows.kt` (`modelRow` state
  machine). Config dialogs are hand-built `LinearLayout` forms (`:416, :679, :813, :894, :1063`).
- Navigation is the Compose nav DSL: `fragment<VoiceSettingsFragment, Voice>`
  (`ui/main/settings/SettingsRoute.kt:226`); `MainActivity` actions
  `ACTION_AUTHORIZE_SYSTEM_ASR` / `ACTION_REQUEST_RECORD_AUDIO_PERMISSION` are the existing
  IME→settings escalation paths.
- Cost of a new "post processing" section = rows + a pure-logic file + prefs; no navigation rewrite.

## 7. Network clients
- `NetworkAsrBackend.http` — shared `OkHttpClient`, connect 10 s (`NetworkAsrBackend.kt:159-161`);
  `ModelJobs.http` — connect 15 s / read 60 s (`LocalModels.kt:63-68`).
- Timeouts: `DEFAULT_FINAL_TIMEOUT_MS = 8_000`, `SELF_HOSTED_FINAL_TIMEOUT_MS = 20_000`
  (`NetworkAsrBackend.kt:152-156`), `OPENAI_FINAL_TIMEOUT_MS = 60_000` (`VoiceInputComponent.kt:421`),
  `MAX_SESSION_MS = 60_000` in every capture-owning backend.
- Cleartext is gated by `BuildConfig.DEBUG` everywhere (`SelfHosted.kt:114`, `LocalModels.kt:96,171`);
  a debug-only `network_security_config_debug.xml` permits cleartext with system trust anchors and
  no release override.
- `OpenAiTranscriptionClient.kt:32-38` (https multipart POST + bearer token, request/response
  parsing) is the closest existing template for a cloud chat-completions call.

## 8. Local native runtime pattern
- sherpa-onnx is a **prebuilt AAR dependency**, not built here:
  `implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:${libs.versions.sherpaOnnx.get()}@aar")`
  (`app/build.gradle.kts:145`, `gradle/libs.versions.toml:12` → `sherpaOnnx = "1.13.8"`), resolved
  from a GitHub-release-asset maven repo with a module filter
  (`settings.gradle.kts:16-27`, `includeModule("com.k2fsa.sherpa.onnx", "sherpa-onnx")`).
  `LocalAsrEngines.kt:19` — "sherpa-onnx v1.13.8 is included in every build variant"; JNI/`.so`
  live inside the AAR and Kotlin calls `com.k2fsa.sherpa.onnx.*` (`LocalAsrEngines.kt:8-16`).
- The project's own `externalNativeBuild`/CMake (`app/build.gradle.kts:34-47`,
  `app/src/main/cpp/CMakeLists.txt`) builds only fcitx5 core (`native-lib`, `androidfrontend`,
  `copy-fcitx5-modules`) and is unrelated to inference.
- ⇒ An on-device LLM runtime most naturally follows the AAR/prebuilt-`.so` precedent rather than
  the CMake route. ONNX Runtime is therefore already present in the APK via the sherpa AAR.

## 9. Footprint numbers read from the pinned catalog (`LocalModelInstaller.kt:78-140`)
- FunAsrNano files: encoder_adaptor 237,792,748 + llm 600,356,593 + embedding 155,584,380
  + tokenizer (2,776,833 + 1,671,853 + 11,422,654 = 15,871,340) = **1,009,605,061 B
  ≈ 1.01 GB (≈0.94 GiB)** installed. Its `llm.int8.onnx` + `Qwen3-0.6B/`
  tokenizer confirm an LLM-decoding ASR (`LocalAsrEngines.kt:110-123`).
- XAsrOffline: 161,015,713 + 11,309,084 + 2,581,422 + 58,806 = **174,965,025 B ≈ 175 MB**, archive
  136,396,739 B (sha256 5d02c36d…).
- XAsrStreaming960: 155,276,576 + 11,309,084 + 2,581,422 + 58,806 = **169,225,888 B ≈ 169 MB**,
  archive 133,895,831 B (sha256 0a92b798…).
- License/limitation records already carried per entry, incl. FunASR Nano
  "Apache-2.0 (ModelScope metadata; exporter source has no LICENSE)" and its
  "empty final results for about 34–39 second utterances" limitation (`:92-95`).
