/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinDictionaryCatalogTest {

    @Test
    fun releaseEntriesCarryPinnedArtifactAndSourceMetadata() {
        assertEquals("dictionary-v1.0.0", PinyinDictionaryCatalog.RELEASE_TAG)
        assertEquals(2, PinyinDictionaryCatalog.entries.size)
        PinyinDictionaryCatalog.entries.forEach { entry ->
            assertTrue(entry.url.startsWith("https://"))
            assertEquals(64, entry.sha256.length)
            assertEquals(40, entry.sourceRevision.length)
            assertEquals(64, entry.sourceInputSha256.length)
            assertTrue(entry.size > 0)
            assertTrue(entry.license.isNotBlank())
        }
    }
}
