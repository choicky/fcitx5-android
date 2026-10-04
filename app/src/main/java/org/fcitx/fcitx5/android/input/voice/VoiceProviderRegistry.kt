/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.speech.SpeechRecognizer
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/** One explicitly registered in-process voice provider. This is not a plugin API. */
internal interface VoiceProvider {
    val id: String
    fun isAvailable(context: Context): Boolean
    fun createBackend(context: Context): VoiceBackend
}

enum class VoiceProviderId(override val stringRes: Int) : ManagedPreferenceEnum {
    System(R.string.voice_provider_system);
}

/** Static provider lookup used by the common voice trigger. */
internal class VoiceProviderRegistry(
    private val providers: List<VoiceProvider>
) {
    fun find(id: String): VoiceProvider? = providers.firstOrNull { it.id == id }

    fun resolve(id: VoiceProviderId): VoiceProvider? = find(id.name.lowercase())

    fun firstAvailable(context: Context): VoiceProvider? =
        providers.firstOrNull { it.isAvailable(context) }
}

internal object SystemVoiceProvider : VoiceProvider {
    const val ID = "system"

    override val id = ID

    override fun isAvailable(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

    override fun createBackend(context: Context): VoiceBackend = SystemAsrBackend(context)
}

internal fun systemVoiceProviderRegistry() = VoiceProviderRegistry(listOf(SystemVoiceProvider))
