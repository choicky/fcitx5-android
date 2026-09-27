/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val prefs = AppPrefs.getInstance()
        // toggling the switch is an answer to the System ASR disclosure, so a turned-off switch
        // is a decline that Auto respects instead of asking again
        screen.findPreference<Preference>(prefs.voice.systemAsrAllowed.key)
            ?.setOnPreferenceChangeListener { _, _ ->
                prefs.internal.voiceSystemAsrAnswered.setValue(true)
                true
            }
    }
}
