/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import timber.log.Timber
import java.io.File

/** Reads and writes the D034 selection; migrates the earlier Auto/Local/System setting once. */
internal class VoiceSelectionStore(
    private val prefs: AppPrefs,
    /** Where [lastError] is kept; resolved on first use (a Context may not exist yet). */
    lastErrorFile: () -> File
) {

    private val lastErrorRecord = LastErrorRecord(lastErrorFile)

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
            internal.voiceEnabledLocalModels.getValue().split(',')
                .mapNotNull { AsrServiceId.parse(it) }
                .filterTo(this) { it is AsrServiceId.Local }
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
        internal.voiceEnabledLocalModels.setValue(
            selection.enabled.filterIsInstance<AsrServiceId.Local>().joinToString(",") { it.key }
        )
        internal.voiceEnabledExternal.setValue(
            selection.enabled.filter { it.external }.joinToString(",") { it.key }
        )
        internal.voiceRecommendationDone.setValue(selection.recommendationDone)
    }

    /** Record the System ASR disclosure answer and what it means for the selection. */
    fun answerSystemDisclosure(allowed: Boolean) {
        val selection = load()
        prefs.voice.systemAsrAllowed.setValue(allowed)
        prefs.internal.voiceSystemAsrAnswered.setValue(true)
        save(selection.afterSystemDisclosure(allowed))
    }

    var instances: List<SelfHostedInstance>
        get() = SelfHostedInstance.decode(prefs.internal.voiceSelfHostedInstances.getValue())
        set(value) = prefs.internal.voiceSelfHostedInstances.setValue(SelfHostedInstance.encode(value))

    /**
     * Removes an instance and its token. The saved current selection is kept, so a removed
     * current instance reads as unavailable instead of silently switching services.
     */
    fun removeInstance(id: String, credentials: CredentialStore) {
        val instance = instances.firstOrNull { it.id == id } ?: return
        instances = instances - instance
        credentials.clear(instance.credentialProvider)
        save(load().withEnabled(instance.service, false))
    }

    /** What resolution may know about external services; no secret is read here. */
    fun externalServices(credentials: CredentialStore, allowCleartext: Boolean): ExternalServices {
        val known = instances
        return object : ExternalServices {
            override fun configured(service: AsrServiceId) = when (service) {
                AsrServiceId.Doubao -> credentials.has(DoubaoCredentials.PROVIDER)
                AsrServiceId.Qwen -> credentials.has(QwenAsrConfig.PROVIDER)
                AsrServiceId.Tencent -> credentials.has(TencentAsrConfig.PROVIDER)
                else -> false
            }

            override fun instance(id: String) = known.firstOrNull { it.id == id }

            override val allowCleartext = allowCleartext
        }
    }

    /**
     * The most recent failure of a service, shown in settings so an invalid or revoked key is
     * visible after the toast is gone. Details are redacted and kept outside shared preferences
     * (not backed up or exported); a value from the earlier preference is discarded.
     */
    var lastError: Pair<String, String>?
        get() {
            dropLegacyLastError()
            return lastErrorRecord.read()
        }
        set(value) {
            dropLegacyLastError()
            // only a diagnostic: a failed write is logged, the voice session is not affected
            if (!lastErrorRecord.write(value)) Timber.w("could not store the last voice error")
        }

    private fun dropLegacyLastError() {
        prefs.purgeLegacyVoiceLastError()
    }

    var lastUsedService: String
        get() = prefs.internal.voiceLastUsedService.getValue()
        set(value) = prefs.internal.voiceLastUsedService.setValue(value)

    private fun migrate() {
        val internal = prefs.internal
        if (!internal.voiceSelectionMigrated.getValue()) {
            // from the Auto/Local/System setting (fb3b0c26); an old Local becomes the Developer
            // screen's research model
            val legacy = internal.voiceLegacyAsrProvider.getValue().ifEmpty { null }
            save(migrateLegacyProvider(legacy, systemAuthorization, localAsrModel(internal.voiceLocalAsr.getValue())))
            internal.voiceSelectionMigrated.setValue(true)
            internal.voiceLocalModelsMigrated.setValue(true)
            return
        }
        if (!internal.voiceLocalModelsMigrated.getValue()) {
            // from one Local service (switch + configured model) to one service per model
            val (current, local) = migrateLocalModels(
                internal.voiceCurrentService.getValue(),
                internal.voiceLocalEnabled.getValue(),
                localAsrModel(internal.voiceLocalModel.getValue())
            )
            internal.voiceCurrentService.setValue(current?.key ?: "")
            internal.voiceEnabledLocalModels.setValue(local.joinToString(",") { it.key })
            internal.voiceLocalModelsMigrated.setValue(true)
        }
    }

    companion object {
        fun lastErrorFile(context: Context) = context.noBackupFilesDir.resolve("voice/last-error")
    }
}
