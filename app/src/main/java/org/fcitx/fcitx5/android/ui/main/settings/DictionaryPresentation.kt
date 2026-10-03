/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary

/** Ephemeral facts used by the dictionary manager UI. */
internal object DictionaryPresentation {
    enum class Section { Builtin, Imported }
    enum class Tap { None, Detail, Select }

    data class Row(
        val dictionary: PinyinDictionary,
        val section: Section,
        val name: String,
        val enabled: Boolean? = null,
        val required: Boolean = false,
        val manageable: Boolean = false,
        val removable: Boolean = false,
    ) {
        fun tap(multiselect: Boolean): Tap = when {
            multiselect -> if (removable) Tap.Select else Tap.None
            manageable -> Tap.Detail
            else -> Tap.None
        }
    }

    fun rows(entries: List<PinyinDictionary>): List<Row> =
        entries.map(::row).sortedWith(compareBy<Row> { it.section.ordinal }.thenBy { it.name })

    fun row(dictionary: PinyinDictionary): Row = when (dictionary) {
        is BuiltinDictionary -> Row(
            dictionary, Section.Builtin, dictionary.name, required = true
        )
        is LibIMEDictionary -> Row(
            dictionary, Section.Imported, dictionary.name,
            enabled = dictionary.isEnabled, manageable = true, removable = true
        )
        else -> Row(dictionary, Section.Imported, dictionary.name)
    }
}
