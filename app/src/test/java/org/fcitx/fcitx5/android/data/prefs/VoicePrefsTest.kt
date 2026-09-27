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
import org.junit.Test
import java.lang.reflect.Proxy

class VoicePrefsTest {

    /** SharedPreferences backed by [values]; edits apply immediately. */
    private fun prefs(values: MutableMap<String, Any>): SharedPreferences {
        val editor = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putString", "putInt", "putFloat", "putLong" -> {
                    values[args!![0] as String] = args[1]
                    proxy
                }
                "apply" -> Unit
                "commit" -> true
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

    private fun store(values: MutableMap<String, Any>) = VoiceSelectionStore(AppPrefs(prefs(values)))

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
}
