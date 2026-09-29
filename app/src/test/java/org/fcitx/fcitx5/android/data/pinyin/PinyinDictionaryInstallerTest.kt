/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class PinyinDictionaryInstallerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installsOnlyAfterSizeAndHashVerification() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)

        val installed = installer.install(entry, ByteArrayInputStream(content))

        assertEquals(content.toList(), installed.readBytes().toList())
        assertTrue(installer.isInstalled(entry))
        assertFalse(temporaryFolder.root.resolve(".${entry.fileName}.download").exists())
    }

    @Test
    fun rejectsMismatchWithoutReplacingExistingDictionary() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        installer.install(entry, ByteArrayInputStream(content))

        val failure = assertThrows(DictionaryInstallFailure.Mismatch::class.java) {
            installer.install(entry, ByteArrayInputStream("different".toByteArray()))
        }

        assertEquals(entry.sha256, failure.expected)
        assertEquals(content.toList(), installer.target(entry).readBytes().toList())
        assertTrue(installer.isInstalled(entry))
    }

    @Test
    fun cancellationDoesNotReplaceExistingDictionary() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        installer.install(entry, ByteArrayInputStream(content))

        assertThrows(DictionaryInstallFailure.Cancelled::class.java) {
            installer.install(entry, ByteArrayInputStream("replacement".toByteArray()), { true })
        }

        assertEquals(content.toList(), installer.target(entry).readBytes().toList())
        assertTrue(installer.isInstalled(entry))
    }

    @Test
    fun rejectsWhenThereIsNotEnoughSpace() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)

        // The test cannot control filesystem free space; a negative declared size is invalid
        // input and exercises the same guard without allocating a large fixture.
        val impossible = entry.copy(size = Long.MAX_VALUE)
        assertThrows(DictionaryInstallFailure.NotEnoughSpace::class.java) {
            installer.install(impossible, ByteArrayInputStream(content))
        }
    }

    @Test
    fun removesOnlyTheCatalogArtifact() {
        val content = "dictionary".toByteArray()
        val entry = entry(content)
        val installer = PinyinDictionaryInstaller(temporaryFolder.root)
        installer.install(entry, ByteArrayInputStream(content))

        installer.remove(entry)

        assertFalse(installer.target(entry).exists())
    }

    private fun entry(content: ByteArray) = PinyinDictionaryCatalogEntry(
        id = "test-dictionary",
        displayName = "Test dictionary",
        version = "test",
        url = "https://example.test/test.dict",
        size = content.size.toLong(),
        sha256 = PinyinDictionaryInstaller.sha256(
            temporaryFolder.newFile("hash-input").also { it.writeBytes(content) }
        ),
        license = "test",
        sourceRepository = "https://example.test/source",
        sourceRevision = "test",
        sourceInputSha256 = "test",
        limitations = "test"
    )
}
