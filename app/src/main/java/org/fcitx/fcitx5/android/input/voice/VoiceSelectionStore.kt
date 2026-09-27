/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/** Reads and writes the D034 selection; migrates the earlier Auto/Local/System setting once. */
internal class VoiceSelectionStore(private val prefs: AppPrefs) {

    val systemAuthorization: SystemAsrAuthorization
        get() = SystemAsrAuthorization.of(
            prefs.voice.systemAsrAllowed.getValue(),
            prefs.internal.voiceSystemAsrAnswered.getValue()
        )

    fun load(): VoiceSelection {
        migrate()
        val internal = prefs.internal
        val enabled = buildSet {
            if (internal.voiceSystemEnabled.getValue()) add(AsrServiceId.System)
            if (internal.voiceLocalEnabled.getValue()) add(AsrServiceId.Local)
            internal.voiceEnabledExternal.getValue().split(',')
                .mapNotNull { AsrServiceId.parse(it) }
                .filterTo(this) { it.external }
        }
        return VoiceSelection(
            current = AsrServiceId.parse(internal.voiceCurrentService.getValue()),
            enabled = enabled,
            recommendationDone = internal.voiceRecommendationDone.getValue()
        )
    }

    fun save(selection: VoiceSelection) {
        val internal = prefs.internal
        internal.voiceCurrentService.setValue(selection.current?.key ?: "")
        internal.voiceSystemEnabled.setValue(selection.isEnabled(AsrServiceId.System))
        internal.voiceLocalEnabled.setValue(selection.isEnabled(AsrServiceId.Local))
        internal.voiceEnabledExternal.setValue(
            selection.enabled.filter { it.external }.joinToString(",") { it.key }
        )
        internal.voiceRecommendationDone.setValue(selection.recommendationDone)
    }

    /** The configured Local model; no formal model exists, so this is a research model (D037). */
    var localModel: LocalAsrModel?
        get() = localAsrModel(prefs.internal.voiceLocalModel.getValue())
        set(value) = prefs.internal.voiceLocalModel.setValue(value?.name ?: "")

    /** Record the System ASR disclosure answer and what it means for the selection. */
    fun answerSystemDisclosure(allowed: Boolean) {
        val selection = load()
        prefs.voice.systemAsrAllowed.setValue(allowed)
        prefs.internal.voiceSystemAsrAnswered.setValue(true)
        save(selection.afterSystemDisclosure(allowed))
    }

    var lastUsedService: String
        get() = prefs.internal.voiceLastUsedService.getValue()
        set(value) = prefs.internal.voiceLastUsedService.setValue(value)

    private fun migrate() {
        val internal = prefs.internal
        if (internal.voiceSelectionMigrated.getValue()) return
        val legacy = internal.voiceLegacyAsrProvider.getValue().ifEmpty { null }
        save(migrateLegacyProvider(legacy, systemAuthorization))
        // the Developer A/B research selection becomes the configured Local model
        if (internal.voiceLocalModel.getValue().isEmpty()) {
            localAsrModel(internal.voiceLocalAsr.getValue())?.let { localModel = it }
        }
        internal.voiceSelectionMigrated.setValue(true)
    }
}
