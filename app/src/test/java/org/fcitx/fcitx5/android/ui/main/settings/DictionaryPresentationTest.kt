/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import java.io.File
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryPresentationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun rowsKeepBuiltinAndImportedSectionsAndFlags() {
        val builtinFile = folder.newFile("main.dict")
        val importedFile = folder.newFile("user.dict")
        val rows = DictionaryPresentation.rows(listOf(
            LibIMEDictionary(importedFile), BuiltinDictionary(builtinFile)
        ))
        assertEquals(listOf(DictionaryPresentation.Section.Builtin, DictionaryPresentation.Section.Imported),
            rows.map { it.section })
        assertTrue(rows[0].required)
        assertFalse(rows[0].removable)
        assertTrue(rows[1].manageable)
        assertTrue(rows[1].removable)
        assertEquals(DictionaryPresentation.Tap.Detail, rows[1].tap(false))
        assertEquals(DictionaryPresentation.Tap.Select, rows[1].tap(true))
    }

    @Test
    fun disabledDictionaryUsesStableNameAndCanBeSelected() {
        val file = folder.newFile("user.dict.disable")
        val dictionary = LibIMEDictionary(file)
        val row = DictionaryPresentation.row(dictionary)
        assertEquals("user", row.name)
        assertEquals(false, row.enabled)
        assertEquals(DictionaryPresentation.Tap.Select, row.tap(true))
    }
}
