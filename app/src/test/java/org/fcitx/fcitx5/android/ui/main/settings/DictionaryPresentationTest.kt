/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.data.pinyin.PinyinDictionaryCatalog
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.CatalogPlaceholderDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.StaticPinyinDictionary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryPresentationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun installed(name: String, enabled: Boolean) =
        LibIMEDictionary(folder.newFile("$name.dict${if (enabled) "" else ".disable"}"))

    @Test fun coreAndPackagedObjectsDoNotGainManagementCapabilities() {
        val base = StaticPinyinDictionary(StaticPinyinDictionary.Kind.Base, "Base", "LibIME Base")
        val ext = StaticPinyinDictionary(StaticPinyinDictionary.Kind.ExtensionB, "ExtB", "LibIME ExtB")
        val row = DictionaryPresentation.row(base, false)
        assertEquals(DictionaryPresentation.Section.Core, row.section)
        assertTrue(row.required)
        assertNull(row.enabled)
        assertNull(row.size)
        assertNull(row.catalog)
        assertFalse(row.manageable)
        assertFalse(row.removable)
        assertEquals(true, DictionaryPresentation.row(ext, true).enabled)
        assertEquals(false, DictionaryPresentation.row(ext, false).enabled)
        val builtin = DictionaryPresentation.row(BuiltinDictionary(folder.newFile("packaged.dict")), false)
        assertEquals(DictionaryPresentation.Section.Builtin, builtin.section)
        assertNull(builtin.enabled)
        assertNull(builtin.entryCount)
        assertNull(builtin.catalog)
        assertFalse(builtin.manageable)
    }

    @Test fun installedCatalogStatesUseLocalSizeAndRetainCatalogProvenanceOnly() {
        listOf(true, false).forEach { enabled ->
            val row = DictionaryPresentation.row(installed("rime-frost", enabled), false)
            assertEquals(DictionaryPresentation.Section.Catalog, row.section)
            assertEquals(enabled, row.enabled)
            assertTrue(row.installed)
            assertTrue(row.removable)
            assertEquals(0L, row.size)
            assertEquals(2_010_605L, row.entryCount)
            assertEquals(PinyinDictionaryCatalog.RELEASE_TAG, row.catalog!!.version)
            assertEquals(DictionaryPresentation.Acquisition.None, row.acquisition)
        }
    }

    @Test fun everyCatalogObjectRemainsVisibleBeforeInstallationWithItsRealAction() {
        val placeholders = PinyinDictionaryCatalog.entries.map { CatalogPlaceholderDictionary(it.id, it.displayName) }
        val rows = DictionaryPresentation.rows(placeholders, false)
        assertEquals(PinyinDictionaryCatalog.entries.map { it.id }, rows.map { it.catalog!!.id })
        rows.forEach { row ->
            assertEquals(DictionaryPresentation.Section.Catalog, row.section)
            assertFalse(row.installed)
            assertNull(row.enabled)
            assertFalse(row.removable)
            assertEquals(row.catalog!!.size, row.size)
            assertEquals(if (row.catalog.privateImportOnly) DictionaryPresentation.Acquisition.ResearchImport
                else DictionaryPresentation.Acquisition.Download, row.acquisition)
        }
    }

    @Test fun importedObjectsHaveOnlyFileFactsAndRemainSeparate() {
        listOf(true, false).forEach { enabled ->
            val row = DictionaryPresentation.row(installed("my-words", enabled), false)
            assertEquals(DictionaryPresentation.Section.Imported, row.section)
            assertEquals(enabled, row.enabled)
            assertNull(row.catalog)
            assertNull(row.entryCount)
            assertNull(row.canonicalName)
            assertTrue(row.manageable)
            assertTrue(row.removable)
        }
    }

    @Test fun rowNavigationAndSelectionAreModeDependent() {
        val imported = DictionaryPresentation.row(installed("custom", true), false)
        assertEquals(DictionaryPresentation.Tap.Detail, imported.tap(false))
        assertEquals(DictionaryPresentation.Tap.Select, imported.tap(true))
        val uninstalled = DictionaryPresentation.row(CatalogPlaceholderDictionary("rime-frost", "Frost"), false)
        assertEquals(DictionaryPresentation.Tap.Detail, uninstalled.tap(false))
        assertEquals(DictionaryPresentation.Tap.None, uninstalled.tap(true))
    }

    @Test fun groupingAndCatalogOrderAreStableAcrossInstallationStates() {
        val frost = installed("rime-frost", true)
        val imported = installed("notes", false)
        val base = StaticPinyinDictionary(StaticPinyinDictionary.Kind.Base, "Base", "Base")
        val ice = CatalogPlaceholderDictionary("rime-ice", "Ice")
        val rows = DictionaryPresentation.rows(listOf(imported, frost, ice, base), false)
        assertEquals(listOf(DictionaryPresentation.Section.Core, DictionaryPresentation.Section.Catalog,
            DictionaryPresentation.Section.Catalog, DictionaryPresentation.Section.Imported), rows.map { it.section })
        assertEquals(listOf("rime-ice", "rime-frost"), rows.mapNotNull { it.catalog?.id })
    }
}
