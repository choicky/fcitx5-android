/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileReplaceTest {

    private val dir = Files.createTempDirectory("replace").toFile()
    private val target = dir.resolve("record")
    private val source = dir.resolve("record.tmp")

    /** File.renameTo as on Windows: it does not replace an existing target. */
    private val windowsRename: (File, File) -> Boolean = { from, to -> !to.exists() && from.renameTo(to) }

    private fun leftovers() = dir.list()!!.filter { it != "record" }.sorted()

    @Test
    fun replacesAnExistingTargetWhereRenameCannot() {
        target.writeText("old")
        source.writeText("new")
        assertTrue(FileReplace.replace(source, target, windowsRename))
        assertEquals("new", target.readText())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun aFailedReplacementKeepsThePreviousContentAndReportsIt() {
        target.writeText("old")
        source.writeText("new")
        // moving the target aside works, moving the new file in does not
        val failing: (File, File) -> Boolean = { from, to -> from != source && windowsRename(from, to) }
        assertFalse(FileReplace.replace(source, target, failing))
        assertEquals("old", target.readText())
        assertEquals(listOf("record.tmp"), leftovers())
    }

    @Test
    fun aMissingSourceIsAFailure() {
        target.writeText("old")
        assertFalse(FileReplace.replace(source, target, windowsRename))
        assertEquals("old", target.readText())
    }

    @Test
    fun anInterruptedReplacementIsRecovered() {
        // the process died after moving the target aside
        File(target.path + ".bak").writeText("old")
        assertEquals("old", FileReplace.recover(target).readText())
        assertEquals(emptyList<String>(), leftovers())
        // ... or after moving the new file in: the backup is stale
        File(target.path + ".bak").writeText("older")
        target.writeText("new")
        assertEquals("new", FileReplace.recover(target).readText())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun lastErrorRecordReplacesAnEarlierRecord() {
        // the failure seen on Windows: the second record was not written
        val record = LastErrorRecord { dir.resolve("voice/last-error") }
        assertTrue(record.write("qwen" to "a".repeat(40)))
        assertTrue(record.write("tencent" to "b".repeat(500)))
        assertEquals("tencent", record.read()!!.first)
        assertEquals(ErrorRedaction.MAX_LENGTH, record.read()!!.second.length)
        assertTrue(record.write(null))
        assertEquals(null, record.read())
    }

    @Test
    fun lastErrorRecordReportsAWriteThatCannotHappen() {
        // the parent of the record is a regular file
        dir.resolve("voice").writeText("not a directory")
        assertFalse(LastErrorRecord { dir.resolve("voice/last-error") }.write("qwen" to "detail"))
    }
}
