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
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
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
 * The simplified Voice settings layout, on whatever state the device has: nothing is changed,
 * so the owner's services, credentials and models are kept. Checks one System ASR row, one row
 * per Local model with its enable switch inside it (only when installed), the current service
 * as the row title, and credential rows that only say set or not set.
 */
class VoiceSettingsLayoutTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    private fun openVoiceSettings(): MainActivity {
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.Voice)
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
            val rows = onMain { fragment.category(R.string.voice_section_system).children() }
            assertEquals(1, rows.size)
            assertEquals(app.getString(R.string.asr_provider_system), rows[0].title)
            assertTrue(rows[0].summary.toString().endsWith(app.getString(R.string.voice_system_note)))
        } finally {
            activity.finish()
        }
    }

    @Test
    fun eachLocalModelIsOneRowWithItsSwitchOnlyWhenInstalled() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            val category = onMain { fragment.category(R.string.voice_section_local) }
            assertEquals(app.getString(R.string.voice_section_local), category.title)
            val rows = onMain { category.children().filterIsInstance<ModelRowPreference>() }
            assertEquals(LocalAsrModel.userVisibleEntries.size, rows.size)
            assertEquals(app.getString(R.string.voice_model_c), rows[0].title)
            assertEquals(app.getString(R.string.voice_model_b), rows[1].title)
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
    fun theCurrentServiceIsTheRowTitle() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            val first = onMain { fragment.category(R.string.voice_current_section).children().first() }
            // the selected service (or "none selected") is the title, not a generic label, and a
            // ready service carries no "ready" word
            assertFalse(first.title.isNullOrEmpty())
            assertFalse(first.title.toString() in setOf("语音识别服务", "Speech recognition service"))
            val summary = first.summary.toString()
            assertFalse(summary.startsWith("可用") || summary.contains(" · 可用") || summary.startsWith("Ready"))
        } finally {
            activity.finish()
        }
    }

    @Test
    fun credentialRowsOnlySayWhetherSomethingIsSet() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            val titles = listOf(R.string.voice_doubao_credentials, R.string.voice_qwen_credentials, R.string.voice_tencent_credentials)
                .map { app.getString(it) }
            val allowed = setOf(
                app.getString(R.string.voice_credentials_set),
                app.getString(R.string.voice_credentials_missing)
            )
            val rows: List<Preference> = onMain {
                fragment.category(R.string.voice_section_cloud).children().filter { it.title in titles }
            }
            assertEquals(3, rows.size)
            rows.forEach { assertTrue(it.summary.toString() in allowed) }

            val switchTitles = listOf(
                R.string.voice_enable_doubao,
                R.string.voice_enable_qwen,
                R.string.voice_enable_tencent
            ).map(app::getString).toSet()
            val switches = onMain {
                fragment.category(R.string.voice_section_cloud).children()
                    .filter { it.title.toString() in switchTitles }
            }
            assertEquals(3, switches.size)
            assertTrue(switches.none { it.title.toString().startsWith("启用") || it.title.toString().startsWith("Use ") })
        } finally {
            activity.finish()
        }
    }
}
