/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.widget.CompoundButton
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.fcitx.fcitx5.android.R

/**
 * One Local model row: tapping the row opens the model's actions; an installed model's enable
 * switch sits at the end of the same row and toggles on its own. Progress changes only the
 * summary of this Preference, so the list keeps its items and scroll position.
 */
internal class ModelRowPreference(context: Context) : Preference(context) {

    /** Null hides the switch (a model that is not installed cannot be enabled). */
    var enable: Enable? = null
        set(value) {
            field = value
            widgetLayoutResource = if (value != null) R.layout.voice_model_switch else 0
        }

    class Enable(val checked: Boolean, val description: CharSequence, val onChange: (Boolean) -> Unit)

    init {
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val switch = holder.findViewById(R.id.voice_model_switch) as? CompoundButton ?: return
        val enable = enable ?: return
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = enable.checked
        switch.contentDescription = enable.description
        switch.setOnCheckedChangeListener { _, on -> enable.onChange(on) }
    }
}
