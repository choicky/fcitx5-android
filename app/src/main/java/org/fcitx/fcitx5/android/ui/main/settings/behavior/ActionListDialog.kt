/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * A titled list of actions. It deliberately has no message: AlertDialog attaches its item list
 * only when no message is set, so a message would hide every action (a device-observed bug in
 * the Model Manager, 2026-09-28). Details go into a separate dialog or a confirmation instead.
 */
internal object ActionListDialog {

    fun create(
        context: Context,
        title: CharSequence,
        labels: List<CharSequence>,
        onPick: (Int) -> Unit
    ): AlertDialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setItems(labels.toTypedArray()) { _, which -> onPick(which) }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
}
