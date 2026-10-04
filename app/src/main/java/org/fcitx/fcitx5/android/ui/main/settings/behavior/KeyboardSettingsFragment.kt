/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.R

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    private val toolbarActions = AppPrefs.getInstance().internal.toolbarActions
    private var voiceSwitch: MySwitchPreference? = null
    private val toolbarActionsListener = ManagedPreference.OnChangeListener<String> { _, _ ->
        refreshVoiceSwitch()
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        super.onPreferenceUiCreated(screen)
        val switch = MySwitchPreference(screen.context).apply {
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setTitle(R.string.show_voice_input_button)
            setSummary(R.string.show_voice_input_button_summary)
            setOnPreferenceChangeListener { _, value ->
                val actions = ToolbarAction.decode(toolbarActions.getValue())
                toolbarActions.setValue(
                    ToolbarAction.encode(ToolbarAction.withVoice(actions, value as Boolean))
                )
                toolbarActions.fireChange()
                true
            }
        }
        voiceSwitch = switch
        val keepLettersKey = AppPrefs.getInstance().keyboard.keepLettersUppercase.key
        val keepLettersIndex = (0 until screen.preferenceCount).firstOrNull {
            screen.getPreference(it).key == keepLettersKey
        } ?: -1
        for (index in 0 until screen.preferenceCount) {
            screen.getPreference(index).order = index * 10
        }
        switch.order = ((keepLettersIndex + 1).coerceAtLeast(0) * 10) + 1
        screen.addPreference(switch)
        refreshVoiceSwitch()
    }

    override fun onResume() {
        super.onResume()
        refreshVoiceSwitch()
    }

    override fun onStart() {
        super.onStart()
        toolbarActions.registerOnChangeListener(toolbarActionsListener)
    }

    override fun onStop() {
        toolbarActions.unregisterOnChangeListener(toolbarActionsListener)
        super.onStop()
    }

    private fun refreshVoiceSwitch() {
        voiceSwitch?.isChecked = ToolbarAction.Voice in
            ToolbarAction.decode(toolbarActions.getValue())
    }
}
