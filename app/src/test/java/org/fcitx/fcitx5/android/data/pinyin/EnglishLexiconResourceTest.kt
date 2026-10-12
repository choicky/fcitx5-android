/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

class EnglishLexiconResourceTest {
    private fun reader(text: String): BufferedReader = BufferedReader(StringReader(text))

    @Test fun countsDataLinesAndSkipsHeaderCommentsAndBlanks() {
        val tsv = buildString {
            appendLine("# english_lexicon.tsv - production English system lexicon")
            appendLine("# source: Rime Ice en_dicts")
            appendLine("ai\tAI\t9\tt")
            appendLine("ui\tUI\t9\tt")
            appendLine("")
            appendLine("# trailing comment")
            appendLine("kubernetes\tKubernetes\t7\tP")
        }
        assertEquals(3L, EnglishLexiconResource.countEntries(reader(tsv)))
    }

    @Test fun emptyResourceCountsZero() {
        assertEquals(0L, EnglishLexiconResource.countEntries(reader("")))
    }

    @Test fun fileIsPlacedBesideThePinyinDictionaryDir() {
        val dictDir = File("/data/user/0/pkg/usr/share/fcitx5/pinyin/dictionaries")
        val file = EnglishLexiconResource.file(dictDir)
        assertEquals("english_lexicon.tsv", file.name)
        assertEquals(
            "/data/user/0/pkg/usr/share/fcitx5/pinyin/english_lexicon.tsv",
            file.path
        )
    }

    @Test fun entryCountIsNullForMissingResource() {
        assertNull(EnglishLexiconResource.entryCount(File("/no/such/english_lexicon.tsv")))
    }
}
