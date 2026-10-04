/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.SystemVoiceProvider

class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<Preference>("voice_provider")?.summary =
            getString(R.string.voice_provider_system)
        screen.addPreference(
            Preference(requireContext()).apply {
                key = "voice_provider_status"
                title = getString(R.string.voice_provider_status)
                summary = if (SystemVoiceProvider.isAvailable(requireContext())) {
                    getString(R.string.voice_provider_available)
                } else {
                    getString(R.string.voice_provider_unavailable)
                }
                isSelectable = false
                isIconSpaceReserved = false
            }
        )
    }
}
