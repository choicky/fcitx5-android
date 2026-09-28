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

        val local = mutableMapOf<String, Any>("voice_asr_provider" to "Local", "voice_local_asr" to "FunAsrNano")
        store(local).run {
            assertEquals(AsrServiceId.Local, load().current)
            // the Developer research selection becomes the configured Local model
            assertEquals(LocalAsrModel.FunAsrNano, localModel)
        }

        // old Auto after a decline: remembered, nothing selected
        val declined = mutableMapOf<String, Any>(
            "voice_asr_provider" to "Auto", "voice_system_asr_answered" to true
        )
        assertEquals(VoiceSelection(null, emptySet(), true), store(declined).load())

        // migration runs once: later edits are not overwritten by the legacy value
        store(system).save(VoiceSelection(AsrServiceId.Local, setOf(AsrServiceId.Local), true))
        assertEquals(AsrServiceId.Local, store(system).load().current)
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
}
