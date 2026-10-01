# Dictionary and Voice Settings UI V2

Accepted implementation boundary (2026-10-02): presentation and navigation only.

Dictionary Manager uses a dictionary-specific sectioned object list and a
conditional detail screen inside the existing manager Fragment. This keeps
the manager's import, transient download dialog and deferred reload ownership.
Base/core, packaged built-in, third-party and imported sections are derived
presentation groups. Every current catalog entry is visible, installed or not;
private research entries retain their existing import-only path.

Normal manageable row taps open detail; switches only change enabled state.
Toolbar Edit retains multi-select deletion and installed user files retain
swipe deletion. Explicit detail deletion requires confirmation. The + FAB and
existing download chooser/disclosure/progress dialog remain. Detail omits
unsupported fields; catalog version never means installed/current/latest
version. No update, repair, rollback, provenance storage or persisted download
tasks are introduced. This supersedes the flat settings-row presentation,
while preserving the existing dictionary operations and metadata provenance.

Dictionary implementation: `DictionaryPresentation` derives row facts and tap
policy; `DictionaryManagerUi` renders headers and rows with source-index-safe
swipe/multi-select removal. `PinyinDictionaryFragment` presents scrollable
conditional detail, restores the local detail selection across configuration
changes, and delegates all operations to its existing paths. No route/domain
or installer changes are required. Additional behavioral changes: None beyond
the approved normal row navigation and detail delete confirmation.

Voice Settings uses five presentation sections: input methods, recognition
service, local recognition, cloud recognition and other recognition services.
Its trigger controls mirror the canonical Keyboard Settings preferences.
The current-service row opens the existing selector; recommendation remains an
action. System and Self-hosted are grouped visually without changing provider
types. Existing capabilities are reorganized; missing capabilities are omitted.
No Dictionary/Voice management framework is introduced.

Voice implementation keeps the existing Preference Fragment and provider names.
The selector label carries the concrete selected service and reason in its
summary. Three cloud object rows open a provider-specific enabled/credential
surface that delegates to the original secure editor. Local rows open scrollable
management details with the existing action set, installed-only enabling,
metadata and notices. Progress updates the stable Preference/detail status;
controls refresh on status changes. System retains its action/disclosure surface;
Self-hosted retains its instance forms under Other. No connection test, provider
capability, catalog/state store, fallback or runtime pipeline was added.

Validation: Dictionary CI run `36906187024` passed JVM tests, debug APK, release
Kotlin, instrumented-test compilation, APK content and signature assertions.
OracleKR3 has no Android SDK; local Android Gradle tests stop at configuration.
Instrumented tests are compiled by CI but have not run on a device. Voice and
combined batch results are in the implementation reports; no device acceptance
is inferred from compilation. Historical Voice/Local device PASS remains a
record for its original commits, not acceptance of this presentation change.

Implementation and validation results are recorded per batch. Physical-device
acceptance remains **TBV**: density, wrapping, large fonts, dark mode, touch and
accessibility, navigation/refresh, import/download/deletion and Pinyin/Shuangpin;
Voice also requires both independent triggers, provider/recommendation paths,
model lifecycle presentation, authorization and session start/stop/cancel.
