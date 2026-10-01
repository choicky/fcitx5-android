/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.widget.CompoundButton
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.ListPreference
import androidx.preference.SwitchPreference
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.voice.LocalAsrModel
import org.fcitx.fcitx5.android.input.voice.LocalModels
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice layout on the device's existing state. Provider/credential/model state is kept;
 * the trigger mirror test restores its temporary preference edits. Checks one System row, one row
 * per Local model with its enable switch inside it (only when installed), the current service
 * in the selector summary, and compact provider rows with no credential values.
 */
class VoiceSettingsLayoutTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    private fun openVoiceSettings(): MainActivity = openSettings(SettingsRoute.Voice)

    private fun openSettings(route: SettingsRoute): MainActivity {
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return instrumentation.startActivitySync(intent) as MainActivity
    }

    private fun MainActivity.voiceSettings(): VoiceSettingsFragment {
        val deadline = System.currentTimeMillis() + 10_000
        var found: VoiceSettingsFragment? = null
        while (found == null) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for the Voice settings page" }
            instrumentation.runOnMainSync {
                found = supportFragmentManager.fragments
                    .flatMap { it.childFragmentManager.fragments }
                    .filterIsInstance<VoiceSettingsFragment>()
                    .firstOrNull { it.isResumed }
            }
            if (found == null) Thread.sleep(20)
        }
        instrumentation.waitForIdleSync()
        return found!!
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun VoiceSettingsFragment.category(title: Int): PreferenceCategory {
        val screen = preferenceScreen
        return screen.children()
            .filterIsInstance<PreferenceCategory>()
            .first { it.title == app.getString(title) }
    }

    private fun PreferenceGroup.children(): List<Preference> = (0 until preferenceCount).map { getPreference(it) }

    @Test
    fun systemAsrIsOneRowWithTheDisclosure() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            val rows = onMain { fragment.category(R.string.voice_v2_section_other).children() }
            val system = rows.single { it.key == "voice_system_row" }
            assertEquals(app.getString(R.string.asr_provider_system), system.title)
            assertTrue(system.summary.toString().endsWith(app.getString(R.string.voice_system_note)))
            assertTrue(rows.any { it.title == app.getString(R.string.voice_selfhosted_add) })
        } finally {
            activity.finish()
        }
    }

    @Test
    fun eachLocalModelIsOneRowWithItsSwitchOnlyWhenInstalled() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            val category = onMain { fragment.category(R.string.voice_v2_section_local) }
            assertEquals(app.getString(R.string.voice_v2_section_local), category.title)
            val rows = onMain { category.children().filterIsInstance<ModelRowPreference>() }
            assertEquals(LocalAsrModel.userVisibleEntries.size, rows.size)
            assertEquals(app.getString(R.string.voice_model_b), rows[0].title)
            assertEquals(app.getString(R.string.voice_model_c), rows[1].title)
            rows.forEach {
                assertFalse(it.title.toString().matches(Regex("^[ABC][：:]")))
            }
            // no separate switch rows: at most the "no runtime" note besides the model rows
            assertTrue(onMain { category.preferenceCount } <= rows.size + 1)
            for (model in LocalAsrModel.userVisibleEntries) {
                val row = onMain { fragment.findPreference<ModelRowPreference>("voice_model_row_${model.name}") }
                assertNotNull(row)
                // "installed" only after verification: the same check the row uses
                val installed = LocalModels.isInstalled(app, model)
                if (!installed) {
                    assertNull("$model is not installed but offers enabling", row!!.enable)
                    assertEquals(0, row.widgetLayoutResource)
                    continue
                }
                assertNotNull(row!!.enable)
                // the switch is in the row's own view, reflects the stored state and is labelled
                val position = onMain { fragment.listView.adapter!!.let { adapter ->
                    (0 until adapter.itemCount).first { (adapter as PreferenceGroupAdapter).getItem(it) === row }
                } }
                onMain { fragment.listView.scrollToPosition(position) }
                instrumentation.waitForIdleSync()
                val switch = onMain {
                    fragment.listView.findViewHolderForAdapterPosition(position)!!.itemView
                        .findViewById<CompoundButton>(R.id.voice_model_switch)
                }
                assertNotNull(switch)
                assertEquals(row.enable!!.checked, onMain { switch.isChecked })
                assertFalse(onMain { switch.contentDescription.isNullOrEmpty() })
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun currentServiceHasASelectorLabelAndTheSelectedServiceInItsSummary() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            val first = onMain { fragment.category(R.string.voice_v2_section_service).children().first() }
            assertEquals(app.getString(R.string.voice_v2_current_service), first.title)
            assertEquals("voice_current_service_row", first.key)
            val summary = first.summary.toString()
            assertFalse(summary.startsWith("可用") || summary.contains(" · 可用") || summary.startsWith("Ready"))
        } finally {
            activity.finish()
        }
    }

    @Test
    fun cloudProvidersAreThreeCompactObjectsWithoutExposingCredentials() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            val allowed = setOf(
                app.getString(R.string.voice_v2_not_configured),
                app.getString(R.string.voice_v2_configured_disabled),
                app.getString(R.string.voice_v2_configured_enabled),
                app.getString(R.string.voice_status_in_use)
            )
            val rows: List<Preference> = onMain {
                fragment.category(R.string.voice_v2_section_cloud).children()
            }
            assertEquals(3, rows.size)
            rows.forEach { assertTrue(it.summary.toString() in allowed) }

            val switchTitles = listOf(
                R.string.voice_enable_doubao,
                R.string.voice_enable_qwen,
                R.string.voice_enable_tencent
            ).map(app::getString).toSet()
            val switches = onMain {
                fragment.category(R.string.voice_v2_section_cloud).children()
                    .filter { it.title.toString() in switchTitles }
            }
            assertEquals(3, switches.size)
            assertTrue(switches.none { it.title.toString().startsWith("启用") || it.title.toString().startsWith("Use ") })
        } finally {
            activity.finish()
        }
    }

    @Test
    fun fiveSectionsKeepTriggersSelectionLocalCloudAndOtherSeparate() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            val expected = listOf(R.string.voice_v2_section_triggers, R.string.voice_v2_section_service,
                R.string.voice_v2_section_local, R.string.voice_v2_section_cloud, R.string.voice_v2_section_other)
                .map(app::getString)
            val sections = onMain { fragment.preferenceScreen.children().filterIsInstance<PreferenceCategory>() }
            assertEquals(expected, sections.map { it.title.toString() })
            val triggers = onMain { sections[0].children() }
            assertEquals(listOf(app.getString(R.string.show_voice_input_button),
                app.getString(R.string.space_long_press_behavior)), triggers.map { it.title.toString() })
        } finally {
            activity.finish()
        }
    }

    @Test
    fun voiceTriggerControlsWriteTheKeyboardSourceWithoutCouplingOrRebuildingSpaceDialog() {
        val prefs = AppPrefs.getInstance()
        val actions = prefs.internal.toolbarActions
        val space = prefs.keyboard.spaceKeyLongPressBehavior
        val oldActions = actions.getValue()
        val oldSpace = space.getValue()
        val voiceActivity = openVoiceSettings()
        var keyboardActivity: MainActivity? = null
        try {
            val fragment = voiceActivity.voiceSettings()
            val mic = onMain { fragment.category(R.string.voice_v2_section_triggers).children()
                .filterIsInstance<SwitchPreference>().single() }
            val newMic = ToolbarAction.Voice !in ToolbarAction.decode(oldActions)
            assertTrue(onMain { mic.callChangeListener(newMic) })
            instrumentation.waitForIdleSync()
            assertEquals(newMic, ToolbarAction.Voice in ToolbarAction.decode(actions.getValue()))
            assertEquals(oldSpace, space.getValue())
            val projected = onMain { fragment.findPreference<ListPreference>(space.key)!! }
            val newSpace = if (oldSpace == SpaceLongPressBehavior.None) SpaceLongPressBehavior.VoiceInput
                else SpaceLongPressBehavior.None
            val count = onMain { fragment.renderCount }
            assertTrue(onMain { projected.callChangeListener(newSpace.name) })
            instrumentation.waitForIdleSync()
            assertEquals(newSpace, space.getValue())
            assertEquals(newMic, ToolbarAction.Voice in ToolbarAction.decode(actions.getValue()))
            assertEquals(count, onMain { fragment.renderCount })
            assertTrue(projected === onMain { fragment.findPreference<ListPreference>(space.key) })
            voiceActivity.finish()
            keyboardActivity = openSettings(SettingsRoute.VirtualKeyboard)
            instrumentation.waitForIdleSync()
            val keyboard = onMain { keyboardActivity!!.supportFragmentManager.fragments
                .flatMap { it.childFragmentManager.fragments }.filterIsInstance<KeyboardSettingsFragment>().first() }
            val keyboardSpace = onMain { keyboard.findPreference<ListPreference>(space.key)!! }
            assertEquals(newSpace.name, onMain { keyboardSpace.value })
            assertEquals(SpaceLongPressBehavior.entries.map { it.name }, onMain { keyboardSpace.entryValues.toList() })
            val keyboardMic = onMain { keyboard.preferenceScreen.children().filterIsInstance<SwitchPreference>()
                .single { it.title == app.getString(R.string.show_voice_input_button) } }
            assertEquals(newMic, onMain { keyboardMic.isChecked })
        } finally {
            onMain {
                actions.setValue(oldActions)
                actions.fireChange()
                space.setValue(oldSpace)
                space.fireChange()
            }
            voiceActivity.finish()
            keyboardActivity?.finish()
        }
    }
}
