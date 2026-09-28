/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * The export's archive step. The Android part (SharedPreferences writing its XML on commit) is
 * stood in for by [prepare] rewriting the preferences file, as a synchronous commit does.
 */
class UserDataArchiveTest {

    private val root = Files.createTempDirectory("userdata").toFile()
    private val prefsDir = root.resolve("shared_prefs").apply { mkdirs() }
    private val prefsXml = prefsDir.resolve("org.fcitx.fcitx5.android_preferences.xml")
    private val external = root.resolve("external").apply {
        resolve("local-asr/model").mkdirs()
        resolve("local-asr/model/encoder.onnx").writeText("weights")
        resolve("data").mkdirs()
        resolve("data/user.dict").writeText("dict")
    }

    /** Preferences as an upgrade from the earlier version leaves them. */
    private fun upgradedPrefs() = prefsXml.writeText(
        """<map><string name="voice_last_error">tencent
wss://h/asr?secretid=AKIDold</string><boolean name="show_voice_input_button" value="true" /></map>"""
    )

    /** What the synchronous removal of `voice_last_error` writes. */
    private val removeLegacy = {
        prefsXml.writeText(prefsXml.readText().replace(Regex("<string name=\"voice_last_error\">[^<]*</string>"), ""))
        true
    }

    private fun trees() = listOf(
        UserDataArchive.Tree(prefsDir, "shared_prefs"),
        UserDataArchive.Tree(external, "external", setOf("local-asr"))
    )

    private fun entries(zip: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            var e: ZipEntry? = input.nextEntry
            while (e != null) {
                out[e.name] = input.readBytes().decodeToString()
                e = input.nextEntry
            }
        }
        return out
    }

    @Test
    fun anExportRightAfterTheUpgradeDoesNotContainTheLegacyError() {
        upgradedPrefs()
        val dest = ByteArrayOutputStream()
        UserDataArchive.write(dest, removeLegacy, trees()) { }
        val zip = entries(dest.toByteArray())
        val prefs = zip.getValue("shared_prefs/${prefsXml.name}")
        assertFalse(prefs.contains("voice_last_error"))
        assertFalse(prefs.contains("AKIDold"))
        // other preferences are exported as before
        assertTrue(prefs.contains("show_voice_input_button"))
        assertEquals("dict", zip["external/data/user.dict"])
        assertTrue(zip.keys.none { it.contains("local-asr") })
    }

    @Test
    fun aRemovalThatCannotBeWrittenFailsTheExport() {
        upgradedPrefs()
        val dest = ByteArrayOutputStream()
        val failure = runCatching { UserDataArchive.write(dest, { false }, trees()) { } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        // nothing was archived
        assertEquals(0, dest.size())
    }
}
