/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceProviderRegistryTest {

    private val provider = object : VoiceProvider {
        override val id = "test"
        override fun isAvailable(context: Context) = true
        override fun createBackend(context: Context): VoiceBackend = error("not used")
    }

    @Test
    fun lookupUsesStableProviderIdentity() {
        val registry = VoiceProviderRegistry(listOf(provider))
        assertEquals(provider, registry.find("test"))
        assertNull(registry.find("other"))
    }

    @Test
    fun systemProviderUsesStableCanonicalId() {
        assertEquals("system", SystemVoiceProvider.id)
        assertEquals("system", VoiceProviderId.System.name.lowercase())
    }
}
