/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.input.voice.AsrServiceId
import org.fcitx.fcitx5.android.input.voice.LocalAsrModel
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization
import org.fcitx.fcitx5.android.input.voice.VoiceSelection
import org.fcitx.fcitx5.android.input.voice.VoiceSelectionStore
import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class VoicePrefsTest {

    /** SharedPreferences backed by [values]; edits apply immediately unless [commitFails]. */
    private fun prefs(values: MutableMap<String, Any>, commitFails: Boolean = false): SharedPreferences {
        val editor = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putString", "putInt", "putFloat", "putLong" -> {
                    values[args!![0] as String] = args[1]
                    proxy
                }
                "remove" -> {
                    if (!commitFails) values.remove(args!![0] as String)
                    proxy
                }
                "apply" -> Unit
                "commit" -> !commitFails
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean", "getInt", "getFloat", "getLong" ->
                    values[args!![0] as String] ?: args[1]
                "contains" -> (args!![0] as String) in values
                "edit" -> editor
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val lastErrorFile by lazy { tmp.root.resolve("voice/last-error") }

    private fun store(values: MutableMap<String, Any>) =
        VoiceSelectionStore(AppPrefs(prefs(values))) { lastErrorFile }

    @Test
    fun freshInstallHasNoCurrentServiceAndPendingRecommendation() {
        val store = store(mutableMapOf())
        assertEquals(VoiceSelection(null, emptySet(), false), store.load())
        assertEquals(SystemAsrAuthorization.NotAsked, store.systemAuthorization)
    }

    @Test
    fun legacySettingsMigrateOnceWithoutNetworkServices() {
        val system = mutableMapOf<String, Any>("voice_asr_provider" to "System")
        assertEquals(AsrServiceId.System, store(system).load().current)

        // the Developer research selection becomes that model's own service, enabled and current
        val local = mutableMapOf<String, Any>("voice_asr_provider" to "Local", "voice_local_asr" to "FunAsrNano")
        val nano = AsrServiceId.Local(LocalAsrModel.FunAsrNano)
        assertEquals(VoiceSelection(nano, setOf(nano), true), store(local).load())

        // old Auto after a decline: remembered, nothing selected
        val declined = mutableMapOf<String, Any>(
            "voice_asr_provider" to "Auto", "voice_system_asr_answered" to true
        )
        assertEquals(VoiceSelection(null, emptySet(), true), store(declined).load())

        // migration runs once: later edits are not overwritten by the legacy value
        store(system).save(VoiceSelection(nano, setOf(nano), true))
        assertEquals(nano, store(system).load().current)
    }

    /** An install that already had the single Local service (switch + model, current "local"). */
    @Test
    fun theSingleLocalServiceMigratesToItsModelOnce() {
        val values = mutableMapOf<String, Any>(
            "voice_selection_migrated" to true,
            "voice_current_service" to "local",
            "voice_local_enabled" to true,
            "voice_local_model" to "ZipformerZh",
            "voice_system_enabled" to true,
            "voice_enabled_external" to "doubao",
            "voice_recommendation_done" to true
        )
        assertEquals(
            VoiceSelection(null, setOf(AsrServiceId.System, AsrServiceId.Doubao), true),
            store(values).load()
        )
        assertEquals("", values["voice_enabled_local_models"])
        // once: a later change stays
        store(values).save(VoiceSelection(AsrServiceId.Doubao, setOf(AsrServiceId.Doubao), true))
        assertEquals(VoiceSelection(AsrServiceId.Doubao, setOf(AsrServiceId.Doubao), true), store(values).load())
    }

    @Test
    fun removedASelectionIsIgnoredWithoutCompatibilityMigration() {
        val values = mutableMapOf<String, Any>(
            "voice_selection_migrated" to true,
            "voice_local_models_migrated" to true,
            "voice_current_service" to "local:ZipformerZh",
            "voice_enabled_local_models" to "local:ZipformerZh",
            "voice_recommendation_done" to true
        )
        assertEquals(VoiceSelection(null, emptySet(), true), store(values).load())
        assertEquals("local:ZipformerZh", values["voice_current_service"])
        assertEquals("local:ZipformerZh", values["voice_enabled_local_models"])
    }

    @Test
    fun aDisabledSingleLocalServiceStaysDisabled() {
        val values = mutableMapOf<String, Any>(
            "voice_selection_migrated" to true,
            "voice_current_service" to "local",
            "voice_local_enabled" to false,
            "voice_local_model" to "FunAsrNano",
            "voice_recommendation_done" to true
        )
        val nano = AsrServiceId.Local(LocalAsrModel.FunAsrNano)
        // still selected, not enabled: shown as disabled, nothing else chosen
        assertEquals(VoiceSelection(nano, emptySet(), true), store(values).load())
    }

    @Test
    fun disclosureAnswerPersists() {
        val values = mutableMapOf<String, Any>()
        store(values).answerSystemDisclosure(true)
        store(values).run {
            assertEquals(SystemAsrAuthorization.Allowed, systemAuthorization)
            // answered during the first-use recommendation: System becomes current
            assertEquals(VoiceSelection(AsrServiceId.System, setOf(AsrServiceId.System), true), load())
        }
        val declined = mutableMapOf<String, Any>()
        store(declined).answerSystemDisclosure(false)
        store(declined).run {
            assertEquals(SystemAsrAuthorization.Declined, systemAuthorization)
            assertEquals(VoiceSelection(null, emptySet(), true), load())
        }
    }

    @Test
    fun lastErrorRoundTripsAndIsBounded() {
        val values = mutableMapOf<String, Any>()
        store(values).lastError = "qwen" to "InvalidApiKey: Invalid API-key provided."
        assertEquals("qwen" to "InvalidApiKey: Invalid API-key provided.", store(values).lastError)
        store(values).lastError = "tencent" to "x".repeat(500)
        assertEquals(160, store(values).lastError!!.second.length)
        store(values).lastError = null
        assertEquals(null, store(values).lastError)
        assertFalse(lastErrorFile.exists())
    }

    @Test
    fun lastErrorStaysOutOfSharedPreferencesAndIsRedacted() {
        // a value written by the earlier version, which kept raw details in preferences
        val values = mutableMapOf<String, Any>("voice_last_error" to "tencent\nwss://h/asr?secretid=AKIDold")
        val store = store(values)
        assertEquals(null, store.lastError)
        assertFalse(values.containsKey("voice_last_error"))

        store.lastError = "tencent" to "Expected HTTP 101 from wss://asr.cloud.tencent.com/asr/v2/1?secretid=AKIDx&signature=s"
        assertFalse(values.containsKey("voice_last_error"))
        val stored = lastErrorFile.readText()
        assertFalse(stored, stored.contains("AKIDx"))
        assertEquals("tencent" to "Expected HTTP 101 from wss://asr.cloud.tencent.com/asr/v2/1?***", store.lastError)
    }

    /** Upgrade, then export before anything reads the voice settings (UserDataManager.export). */
    @Test
    fun legacyErrorIsRemovedSynchronouslyBeforeExportWithoutTouchingOtherPreferences() {
        val values = mutableMapOf<String, Any>(
            "voice_last_error" to "qwen\nraw detail",
            "voice_asr_provider" to "System",
            "show_voice_input_button" to true
        )
        assertTrue(AppPrefs(prefs(values)).purgeLegacyVoiceLastError())
        assertEquals(mapOf<String, Any>("voice_asr_provider" to "System", "show_voice_input_button" to true), values)
        // nothing to remove: no write at all
        assertTrue(AppPrefs(prefs(values, commitFails = true)).purgeLegacyVoiceLastError())
    }

    @Test
    fun aRemovalThatCannotBeWrittenIsReported() {
        val values = mutableMapOf<String, Any>("voice_last_error" to "qwen\nraw detail")
        assertFalse(AppPrefs(prefs(values, commitFails = true)).purgeLegacyVoiceLastError())
        assertTrue(values.containsKey("voice_last_error"))
    }

    @Test
    fun mirroredTriggerPreferencesShareCanonicalKeysAndRemainIndependentInAllFourStates() {
        val values = mutableMapOf<String, Any>()
        val shared = prefs(values)
        // Two projections read the same store, not separate Voice-specific preferences.
        val keyboard = AppPrefs(shared)
        val voice = AppPrefs(shared)
        for (mic in listOf(false, true)) {
            for (space in listOf(SpaceLongPressBehavior.None, SpaceLongPressBehavior.VoiceInput)) {
                keyboard.keyboard.spaceKeyLongPressBehavior.setValue(space)
                val actions = ToolbarAction.decode(voice.internal.toolbarActions.getValue())
                voice.internal.toolbarActions.setValue(ToolbarAction.encode(ToolbarAction.withVoice(actions, mic)))
                assertEquals(mic, ToolbarAction.Voice in ToolbarAction.decode(keyboard.internal.toolbarActions.getValue()))
                assertEquals(space, voice.keyboard.spaceKeyLongPressBehavior.getValue())
            }
        }
        assertEquals(setOf("toolbar_actions", "space_long_press_behavior"), values.keys)
        assertEquals("space_long_press_behavior", voice.keyboard.spaceKeyLongPressBehavior.key)
    }

    @Test
    fun everyCanonicalSpaceOptionRoundTripsWithoutChangingToolbarOrProviderSelection() {
        val initialActions = ToolbarAction.encode(ToolbarAction.Default)
        val values = mutableMapOf<String, Any>("toolbar_actions" to initialActions,
            "voice_current_service" to "doubao")
        val app = AppPrefs(prefs(values))
        SpaceLongPressBehavior.entries.forEach { option ->
            app.keyboard.spaceKeyLongPressBehavior.setValue(option)
            assertEquals(option, app.keyboard.spaceKeyLongPressBehavior.getValue())
            assertEquals(initialActions, app.internal.toolbarActions.getValue())
            assertEquals("doubao", values["voice_current_service"])
        }
    }
}
