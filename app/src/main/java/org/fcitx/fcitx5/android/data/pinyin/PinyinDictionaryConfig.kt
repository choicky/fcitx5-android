/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.core.RawConfig

/** Accessor for the existing chinese-addons ExtBEnabled option. */
internal object PinyinDictionaryConfig {
    fun extBEnabled(config: RawConfig): Boolean =
        config.findByName("cfg")?.findByName("ExtBEnabled")?.value == "True"

    fun setExtBEnabled(config: RawConfig, enabled: Boolean): RawConfig {
        config.getOrCreate("cfg").getOrCreate("ExtBEnabled").value =
            if (enabled) "True" else "False"
        return config
    }
}
