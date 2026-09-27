/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.input.voice.AsrProvider
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization
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

    private fun authorization(prefs: AppPrefs) = SystemAsrAuthorization.of(
        prefs.voice.systemAsrAllowed.getValue(),
        prefs.internal.voiceSystemAsrAnswered.getValue()
    )

    @Test
    fun defaultsAreAutoAndSystemNotAsked() {
        val prefs = AppPrefs(prefs(mutableMapOf()))
        assertEquals(AsrProvider.Auto, prefs.voice.asrProvider.getValue())
        assertEquals(SystemAsrAuthorization.NotAsked, authorization(prefs))
    }

    @Test
    fun authorizationAnswerPersists() {
        val allowed = mutableMapOf<String, Any>()
        AppPrefs(prefs(allowed)).run {
            // what the disclosure dialog writes on "Allow"
            voice.systemAsrAllowed.setValue(true)
            internal.voiceSystemAsrAnswered.setValue(true)
        }
        assertEquals(SystemAsrAuthorization.Allowed, authorization(AppPrefs(prefs(allowed))))

        val declined = mutableMapOf<String, Any>()
        AppPrefs(prefs(declined)).run {
            voice.systemAsrAllowed.setValue(false)
            internal.voiceSystemAsrAnswered.setValue(true)
        }
        assertEquals(SystemAsrAuthorization.Declined, authorization(AppPrefs(prefs(declined))))
    }

    @Test
    fun storedProviderIsRead() {
        val prefs = AppPrefs(prefs(mutableMapOf("voice_asr_provider" to "Local")))
        assertEquals(AsrProvider.Local, prefs.voice.asrProvider.getValue())
    }
}
