/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.pinyin

import java.io.BufferedReader
import java.io.File

/** Read-only facts about the built-in English vocabulary that Chinese-English
 *  mixed input consults. This is presentation metadata only: the vocabulary is
 *  owned by the native English Core, is never a selectable runtime dictionary,
 *  and nothing here installs, enables, disables or rewrites it. */
object EnglishLexiconResource {

    /** The addons resource installs to
     *  `${FCITX_INSTALL_PKGDATADIR}/pinyin/english_lexicon.tsv`, i.e. the parent
     *  of the built-in Pinyin dictionary directory at runtime. */
    fun file(builtinPinyinDictDir: File): File =
        File(builtinPinyinDictDir.parentFile, "english_lexicon.tsv")

    /** Counts the entries that actually ship: every line that is neither a `#`
     *  header comment nor blank. This mirrors `tools/prepare_english_lexicon.sh`
     *  (the manifest `entries` field), so the UI reports the shipped count
     *  rather than a duplicated constant. */
    fun countEntries(reader: BufferedReader): Long =
        reader.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }.toLong()

    /** Entry count of the shipped resource, or `null` when the resource is
     *  absent or unreadable (a build without the vocabulary shows no English
     *  row rather than a fabricated number). */
    fun entryCount(resource: File): Long? = runCatching {
        if (resource.isFile) resource.bufferedReader().use { countEntries(it) } else null
    }.getOrNull()
}
