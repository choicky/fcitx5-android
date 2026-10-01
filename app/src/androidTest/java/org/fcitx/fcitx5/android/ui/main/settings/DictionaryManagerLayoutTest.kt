/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the real manager route without changing the device's dictionaries. */
class DictionaryManagerLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    @Test fun catalogRowsOpenManagementDetailAndBackRestoresTheSectionedList() {
        val app = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.PinyinDict())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            lateinit var fragment: PinyinDictionaryFragment
            instrumentation.runOnMainSync {
                fragment = activity.supportFragmentManager.fragments
                    .flatMap { it.childFragmentManager.fragments }
                    .filterIsInstance<PinyinDictionaryFragment>().first()
                val recycler = descendants(fragment.requireView()).filterIsInstance<RecyclerView>().first()
                // Core header, Base, ExtB, then catalog header/objects (packaged rows may exist).
                assertTrue(recycler.adapter!!.itemCount >= 9)
                val position = (0 until recycler.adapter!!.itemCount).first { index ->
                    val holder = recycler.adapter!!.createViewHolder(recycler, recycler.adapter!!.getItemViewType(index))
                    @Suppress("UNCHECKED_CAST")
                    (recycler.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>).bindViewHolder(holder, index)
                    descendants(holder.itemView).filterIsInstance<TextView>().any { it.text.contains("Rime-Frost") }
                }
                recycler.scrollToPosition(position)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val name = descendants(fragment.requireView()).filterIsInstance<TextView>()
                    .first { it.text.contains("Rime-Frost") }
                (name.parent.parent as View).performClick()
                val texts = descendants(fragment.requireView()).filterIsInstance<TextView>()
                assertTrue(texts.any { it.text.toString().contains(app.getString(R.string.dictionary_catalog_version)) })
                assertTrue(texts.any { it.text.toString().contains(app.getString(R.string.dictionary_license)) })
                activity.onBackPressedDispatcher.onBackPressed()
                assertNotNull(descendants(fragment.requireView()).filterIsInstance<RecyclerView>().firstOrNull())
            }
        } finally {
            activity.finish()
        }
    }
}
