/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PinyinDictionaryInstallStateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root: File get() = temporaryFolder.root

    @Test
    fun recordsRoundTripAndForget() {
        val state = PinyinDictionaryInstallState(root)
        val entry = entry("rime-frost", "dictionary".toByteArray())

        assertNull(state.load()["rime-frost"])
        state.record(entry)
        val record = state.load()["rime-frost"]
        assertEquals(PinyinDictionaryCatalog.RELEASE_TAG, record?.catalogTag)
        assertEquals(entry.sha256, record?.sha256)

        state.forget(entry.id)
        assertFalse(state.load().containsKey("rime-frost"))
    }

    @Test fun recordedIdentityTracksCatalogContentChanges() {
        val state = PinyinDictionaryInstallState(root)
        val entry = entry("rime-frost", "dictionary".toByteArray())
        state.record(entry)
        assertEquals(entry.sha256, state.load()[entry.id]?.sha256)

        // The next catalog release ships different bytes under the same id: the stored
        // identity no longer matches, which is what the presentation layer reads as
        // "update available".
        val newer = entry("rime-frost", "replacement".toByteArray())
        assertNotEquals(newer.sha256, state.load()[newer.id]?.sha256)
    }

    @Test
    fun migrateRecordsPre5DVerifiedFilesOnly() {
        val state = PinyinDictionaryInstallState(root)
        val content = "dictionary".toByteArray()
        val verified = entry("rime-frost", content)
        val mismatched = entry("zhwiki", "other".toByteArray())
        File(root, verified.fileName).writeBytes(content)
        File(root, "${mismatched.fileName}.disable").writeBytes("user bytes".toByteArray())

        assertTrue(state.migrate(listOf(verified, mismatched)))

        val records = state.load()
        assertEquals(verified.sha256, records["rime-frost"]?.sha256)
        assertFalse(records.containsKey("zhwiki"))
        // Second run is a no-op.
        assertFalse(state.migrate(listOf(verified, mismatched)))
    }

    @Test
    fun recoverRenamesStrandedBackupBack() {
        val state = PinyinDictionaryInstallState(root)
        val entry = entry("rime-frost", "dictionary".toByteArray())
        File(root, ".${entry.fileName}.old").writeBytes("old bytes".toByteArray())

        val recovered = state.recover(listOf(entry))

        assertEquals(listOf(entry.id), recovered)
        assertEquals("old bytes", File(root, entry.fileName).readText())
        assertFalse(File(root, ".${entry.fileName}.old").exists())
    }

    @Test
    fun recoverDropsStaleBackupWhenLiveFileAlreadyMatchesCatalog() {
        val state = PinyinDictionaryInstallState(root)
        val content = "dictionary".toByteArray()
        val entry = entry("rime-frost", content)
        File(root, entry.fileName).writeBytes(content)
        File(root, ".${entry.fileName}.old").writeBytes(content)

        val recovered = state.recover(listOf(entry))

        assertEquals(emptyList<String>(), recovered)
        assertFalse(File(root, ".${entry.fileName}.old").exists())
        assertTrue(File(root, entry.fileName).isFile)
    }

    @Test
    fun recoverKeepsAmbiguousPairWithoutTouchingUserBytes() {
        val state = PinyinDictionaryInstallState(root)
        val entry = entry("rime-frost", "dictionary".toByteArray())
        File(root, entry.fileName).writeBytes("something else".toByteArray())
        File(root, ".${entry.fileName}.old").writeBytes("old bytes".toByteArray())

        val recovered = state.recover(listOf(entry))

        assertEquals(emptyList<String>(), recovered)
        assertEquals("something else", File(root, entry.fileName).readText())
        assertEquals("old bytes", File(root, ".${entry.fileName}.old").readText())
    }

    @Test
    fun malformedStateLinesAreIgnored() {
        File(root, PinyinDictionaryInstallState.STATE_FILE_NAME).writeText(
            "rime-frost\ttag\tv\t${"0".repeat(63)}\nshort-line\n" +
                "zhwiki\ttag\tv1.0\t${"a".repeat(64)}\n"
        )
        val records = PinyinDictionaryInstallState(root).load()
        assertEquals(setOf("zhwiki"), records.keys)
    }

    private fun entry(id: String, content: ByteArray) = PinyinDictionaryCatalogEntry(
        id = id,
        displayName = id,
        version = "test",
        url = "https://example.test/$id.dict",
        size = content.size.toLong(),
        sha256 = PinyinDictionaryInstaller.sha256(
            temporaryFolder.newFile("hash-$id-${content.size}").also { it.writeBytes(content) }
        ),
        license = "test",
        licenseUrl = "https://example.test/license",
        sourceRepository = "https://example.test/source",
        sourceRevision = "test",
        sourceInputSha256 = "test",
        limitations = "test",
        attribution = "test",
        modificationStatement = "test"
    )
}
