/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class SpaceLongPressDefaultTest {

    /** Read-only SharedPreferences backed by a map; enough for ManagedPreference.getValue(). */
    private fun prefs(values: Map<String, String>) = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SharedPreferences::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getString" -> values[args!![0] as String] ?: args[1]
            "contains" -> (args!![0] as String) in values
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SharedPreferences

    private val codec = object : ManagedPreference.StringLikeCodec<SpaceLongPressBehavior> {
        override fun decode(raw: String) = enumValueOf<SpaceLongPressBehavior>(raw)
    }

    private fun resolve(values: Map<String, String>) = ManagedPreference.PStringLike(
        prefs(values), KEY, SpaceLongPressBehavior.Default, codec
    ).getValue()

    @Test
    fun unsetDefaultsToVoiceInput() {
        assertEquals(SpaceLongPressBehavior.VoiceInput, SpaceLongPressBehavior.Default)
        assertEquals(SpaceLongPressBehavior.VoiceInput, resolve(emptyMap()))
    }

    @Test
    fun storedChoiceWins() {
        assertEquals(SpaceLongPressBehavior.None, resolve(mapOf(KEY to "None")))
        assertEquals(SpaceLongPressBehavior.ShowPicker, resolve(mapOf(KEY to "ShowPicker")))
    }

    companion object {
        private const val KEY = "space_long_press_behavior"
    }
}
