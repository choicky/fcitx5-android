/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.view.ContextThemeWrapper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Model Manager's action dialog on a real device. On the vivo (2026-09-28) the dialog showed
 * only the model note and Cancel: with both a message and items, AlertDialog never attaches the
 * item list. The dialog is created (onCreate, layout inflated) but not shown, so no window or
 * user interaction is needed.
 */
class ActionListDialogTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ContextThemeWrapper(
        instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat_DayNight
    )
    private val labels = listOf("Download", "Download from another address…", "Import model files…", "Details")

    @Test
    fun everyActionIsInTheDialog() {
        instrumentation.runOnMainSync {
            val dialog = ActionListDialog.create(context, "A", labels) { }
            dialog.create()
            val list = dialog.listView
            assertEquals(labels.size, list.adapter.count)
            // attached to the dialog's content, not just constructed
            assertNotNull("the action list is not in the dialog", list.parent)
            assertEquals(View.VISIBLE, list.visibility)
            val message = dialog.findViewById<View>(android.R.id.message)
            assertEquals(true, message == null || message.visibility != View.VISIBLE)
        }
    }

    /** The failure this dialog avoids; if AlertDialog ever changes, this test says so. */
    @Test
    fun aMessageDetachesTheItemList() {
        instrumentation.runOnMainSync {
            val dialog = AlertDialog.Builder(context)
                .setTitle("A")
                .setMessage("Research model. …")
                .setItems(labels.toTypedArray(), null)
                .create()
            dialog.create()
            assertNull(dialog.listView.parent)
        }
    }
}
