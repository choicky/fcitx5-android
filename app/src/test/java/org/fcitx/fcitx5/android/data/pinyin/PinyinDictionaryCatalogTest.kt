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
            assertEquals(64, entry.sourceRevision.length)
            assertEquals(64, entry.sourceInputSha256.length)
            assertTrue(entry.size > 0)
            assertTrue(entry.license.isNotBlank())
        }
    }

    @Test
    fun releaseEntriesMatchPublishedReleaseIndex() {
        assertEquals(
            37_322_174L,
            PinyinDictionaryCatalog.find("rime-frost")!!.size
        )
        assertEquals(
            "b4880861161d585b21413fe554aa8f416beb39d68cf4ce3fba728df5fea584ff",
            PinyinDictionaryCatalog.find("rime-frost")!!.sha256
        )
        assertEquals(
            24_683_718L,
            PinyinDictionaryCatalog.find("rime-wanxiang")!!.size
        )
        assertEquals(
            "492a452604f1d63ec1edf5682846291db72f3caadc3b6cc8e51af52fab3772da",
            PinyinDictionaryCatalog.find("rime-wanxiang")!!.sha256
        )
    }
}
