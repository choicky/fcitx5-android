/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The descriptor-driven Pinyin/Shuangpin entry remains one route into the shared manager. */
class PinyinDictionaryEntryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    private fun open(name: String): MainActivity = instrumentation.startActivitySync(
        Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .putExtra(
                MainActivity.EXTRA_SETTINGS_ROUTE,
                SettingsRoute.InputMethodConfig(name, name.lowercase())
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    ) as MainActivity

    @Test
    fun topLevelDictionaryManagerEntryRemainsPresent() {
        val activity = instrumentation.startActivitySync(
            Intent(app, MainActivity::class.java)
                .setAction(Intent.ACTION_RUN)
                .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.Index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            val main = activity.supportFragmentManager.fragments
                .flatMap { it.childFragmentManager.fragments }
                .first { it is org.fcitx.fcitx5.android.ui.main.MainFragment }
            fun containsTitle(group: PreferenceGroup): Boolean = (0 until group.preferenceCount).any { index ->
                val preference = group.getPreference(index)
                preference.title == app.getString(R.string.pinyin_dict) ||
                    (preference is PreferenceGroup && containsTitle(preference))
            }
            val screen = (main as org.fcitx.fcitx5.android.ui.main.MainFragment).preferenceScreen
            assertTrue(containsTitle(requireNotNull(screen)))
        } finally {
            activity.finish()
        }
    }

    @Test
    fun pinyinAndShuangpinExposeTheDescriptorEntryInUpstreamOrder() {
        listOf("Pinyin", "Shuangpin").forEach { displayName ->
            val activity = open(displayName)
            try {
                instrumentation.waitForIdleSync()
                val fragment = activity.supportFragmentManager.fragments
                    .flatMap { it.childFragmentManager.fragments }
                    .filterIsInstance<org.fcitx.fcitx5.android.ui.main.settings.im.InputMethodConfigFragment>()
                    .first()
                val screen = requireNotNull(fragment.preferenceScreen)
                val deadline = System.currentTimeMillis() + 10_000
                while (screen.preferenceCount == 0) {
                    check(System.currentTimeMillis() < deadline) { "timed out loading $displayName config" }
                    Thread.sleep(20)
                    instrumentation.waitForIdleSync()
                }
                val directKeys = (0 until screen.preferenceCount)
                    .map { screen.getPreference(it).key }
                assertTrue(directKeys.contains("DictManager"))
                val dict = directKeys.indexOf("DictManager")
                val custom = directKeys.indexOf("CustomPhrase")
                assertTrue(dict >= 0 && custom >= 0)
                assertEquals(true, dict < custom)
                val managerEntry = screen.findPreference<Preference>("DictManager")
                assertTrue(managerEntry != null)
                instrumentation.runOnMainSync { managerEntry!!.performClick() }
                instrumentation.waitForIdleSync()
                assertTrue(activity.supportFragmentManager.fragments
                    .flatMap { it.childFragmentManager.fragments }
                    .any { it is PinyinDictionaryFragment })
                activity.onBackPressedDispatcher.onBackPressed()
                instrumentation.waitForIdleSync()
                assertTrue(fragment.isResumed)
            } finally {
                activity.finish()
            }
        }
    }
}
