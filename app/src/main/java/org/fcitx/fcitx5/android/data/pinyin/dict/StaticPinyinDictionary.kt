/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import java.io.File

/** A row for a dictionary shipped in the Fcitx5 data set, not a user file. */
class StaticPinyinDictionary(
    val kind: Kind,
    val label: String,
    val canonicalName: String,
) : PinyinDictionary() {
    enum class Kind { Base, ExtensionB }

    override val file: File = File("/fcitx5/static/${kind.name}.dict")
    override val type: Type = Type.LibIME

    override fun toTextDictionary(dest: File): TextDictionary =
        error("Static dictionary cannot be exported")

    override fun toLibIMEDictionary(dest: File): LibIMEDictionary =
        error("Static dictionary cannot be exported")
}

class CatalogPlaceholderDictionary(
    val entryId: String,
    val displayLabel: String,
) : PinyinDictionary() {
    override val file: File = File("/fcitx5/catalog/$entryId.dict")
    override val type: Type = Type.LibIME

    override val name: String
        get() = entryId

    override fun toTextDictionary(dest: File): TextDictionary =
        error("Catalog placeholder has no local file")

    override fun toLibIMEDictionary(dest: File): LibIMEDictionary =
        error("Catalog placeholder has no local file")
}
