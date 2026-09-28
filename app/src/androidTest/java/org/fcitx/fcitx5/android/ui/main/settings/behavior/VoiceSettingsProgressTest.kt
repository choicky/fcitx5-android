/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import androidx.preference.Preference
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.voice.LocalAsrModel
import org.fcitx.fcitx5.android.input.voice.ModelJobs
import org.fcitx.fcitx5.android.input.voice.ModelTasks
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * The Voice settings page while a model task reports progress (vivo, 6007c8ca: the page
 * flickered between its content and a blank page during every A/B/C download). A fake task
 * reports progress without downloading anything. Progress must update the model's row in
 * place: no rebuild of the screen, the same Preference object, the same items and scroll
 * position; the end of the task rebuilds once.
 */
class VoiceSettingsProgressTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun openVoiceSettings(): MainActivity {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.Voice)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return instrumentation.startActivitySync(intent) as MainActivity
    }

    private fun MainActivity.voiceSettings(): VoiceSettingsFragment {
        var found: VoiceSettingsFragment? = null
        waitUntil("the Voice settings page") {
            instrumentation.runOnMainSync {
                found = supportFragmentManager.fragments
                    .flatMap { it.childFragmentManager.fragments }
                    .filterIsInstance<VoiceSettingsFragment>()
                    .firstOrNull { it.isResumed }
            }
            found != null
        }
        return found!!
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
        instrumentation.waitForIdleSync()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun rowKey(model: LocalAsrModel) = "voice_model_row_${model.name}"

    /** Starts a fake task that reports [steps] progress updates after [go] and ends after [end]. */
    private fun fakeTask(model: LocalAsrModel, go: CountDownLatch, end: CountDownLatch, steps: Int = 100) =
        ModelJobs.startForTest(model, steps.toLong()) { handle ->
            go.await()
            for (i in 1..steps) {
                handle.progress(i.toLong(), steps.toLong())
                Thread.sleep(3)
            }
            end.await()
        }

    private fun progressReached(model: LocalAsrModel, done: Long) =
        (ModelJobs.state(model) as? ModelTasks.State.Running)?.done == done

    @Test
    fun progressUpdatesOnlyTheModelRowForEachModel() {
        val activity = openVoiceSettings()
        try {
            val fragment = activity.voiceSettings()
            for (model in LocalAsrModel.entries) {
                val go = CountDownLatch(1)
                val end = CountDownLatch(1)
                assertTrue(fakeTask(model, go, end))
                waitUntil("$model to start") { ModelJobs.isRunning(model) }
                // started: one rebuild for the new status is allowed
                val rendersWhileRunning = onMain { fragment.renderCount }
                val row = onMain { fragment.findPreference<Preference>(rowKey(model)) }
                assertNotNull(row)
                onMain { fragment.listView.scrollToPosition(fragment.listView.adapter!!.itemCount - 1) }
                instrumentation.waitForIdleSync()
                val items = onMain { fragment.listView.adapter!!.itemCount }
                val offset = onMain { fragment.listView.computeVerticalScrollOffset() }

                go.countDown()
                waitUntil("$model progress") { progressReached(model, 100) }

                assertEquals("progress rebuilt the screen", rendersWhileRunning, onMain { fragment.renderCount })
                assertSame(row, onMain { fragment.findPreference<Preference>(rowKey(model)) })
                assertTrue(onMain { row!!.summary.toString() }.contains("100"))
                assertEquals(items, onMain { fragment.listView.adapter!!.itemCount })
                assertEquals(offset, onMain { fragment.listView.computeVerticalScrollOffset() })
                assertEquals(null, onMain { fragment.listView.itemAnimator })

                end.countDown()
                waitUntil("$model to end") { !ModelJobs.isRunning(model) }
                // the end is a new status: exactly one rebuild, not lost
                assertEquals(rendersWhileRunning + 1, onMain { fragment.renderCount })
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun aReopenedPageFollowsARunningTaskInPlace() {
        val model = LocalAsrModel.ZipformerBilingual
        val go = CountDownLatch(1)
        val end = CountDownLatch(1)
        val first = openVoiceSettings()
        first.voiceSettings()
        assertTrue(fakeTask(model, go, end, steps = 200))
        waitUntil("$model to start") { ModelJobs.isRunning(model) }
        // leave the page during the task, then open it again
        instrumentation.runOnMainSync { first.finish() }
        instrumentation.waitForIdleSync()
        val second = openVoiceSettings()
        try {
            val fragment = second.voiceSettings()
            val renders = onMain { fragment.renderCount }
            val row = onMain { fragment.findPreference<Preference>(rowKey(model)) }
            go.countDown()
            waitUntil("$model progress") { progressReached(model, 200) }
            assertEquals(renders, onMain { fragment.renderCount })
            assertSame(row, onMain { fragment.findPreference<Preference>(rowKey(model)) })
            assertTrue(onMain { row!!.summary.toString() }.contains("100"))
        } finally {
            end.countDown()
            waitUntil("$model to end") { !ModelJobs.isRunning(model) }
            second.finish()
        }
    }
}
