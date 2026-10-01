/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.data.pinyin.PinyinDictionaryCatalog
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictionaryCatalogEntry
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.CatalogPlaceholderDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.StaticPinyinDictionary

/** Ephemeral facts for rendering, never an installation or persistence authority. */
internal object DictionaryPresentation {
    enum class Section { Core, Builtin, Catalog, Imported }
    enum class Acquisition { None, Download, ResearchImport }
    enum class Tap { None, Detail, Select }

    data class Row(
        val dictionary: PinyinDictionary,
        val section: Section,
        val name: String,
        val canonicalName: String? = null,
        val catalog: PinyinDictionaryCatalogEntry? = null,
        val enabled: Boolean? = null,
        val required: Boolean = false,
        val installed: Boolean = true,
        val size: Long? = null,
        val entryCount: Long? = null,
        val manageable: Boolean = false,
        val removable: Boolean = false,
        val acquisition: Acquisition = Acquisition.None,
    ) {
        fun tap(multiselect: Boolean): Tap = when {
            multiselect -> if (removable) Tap.Select else Tap.None
            manageable -> Tap.Detail
            else -> Tap.None
        }
    }

    fun rows(entries: List<PinyinDictionary>, extBEnabled: Boolean): List<Row> =
        entries.map { row(it, extBEnabled) }.sortedWith(
            compareBy<Row> { it.section.ordinal }.thenBy {
                it.catalog?.let { catalog -> PinyinDictionaryCatalog.entries.indexOf(catalog) } ?: 0
            }
        )

    fun row(dictionary: PinyinDictionary, extBEnabled: Boolean): Row {
        if (dictionary is StaticPinyinDictionary) {
            val base = dictionary.kind == StaticPinyinDictionary.Kind.Base
            return Row(dictionary, Section.Core, dictionary.label,
                dictionary.canonicalName, enabled = if (base) null else extBEnabled,
                required = base)
        }
        if (dictionary is BuiltinDictionary) {
            return Row(dictionary, Section.Builtin, dictionary.name)
        }
        val catalog = when (dictionary) {
            is CatalogPlaceholderDictionary -> PinyinDictionaryCatalog.find(dictionary.entryId)
            is LibIMEDictionary -> PinyinDictionaryCatalog.find(dictionary.name)
            else -> null
        }
        val installed = dictionary !is CatalogPlaceholderDictionary
        val libime = dictionary as? LibIMEDictionary
        return Row(
            dictionary, if (catalog != null) Section.Catalog else Section.Imported,
            catalog?.displayName ?: dictionary.name, catalog?.canonicalName,
            catalog, libime?.isEnabled, installed = installed,
            size = if (installed) dictionary.file.length() else catalog?.size,
            entryCount = catalog?.entryCount,
            manageable = catalog != null || libime != null,
            removable = libime != null,
            acquisition = when {
                installed || catalog == null -> Acquisition.None
                catalog.privateImportOnly -> Acquisition.ResearchImport
                else -> Acquisition.Download
            }
        )
    }
}
