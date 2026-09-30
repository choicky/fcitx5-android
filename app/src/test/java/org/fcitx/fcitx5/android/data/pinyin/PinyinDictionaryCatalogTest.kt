/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinDictionaryCatalogTest {
    @Test
    fun releaseEntriesCarryPinnedArtifactAndSourceMetadata() {
        assertEquals("dictionary-v1.0.0", PinyinDictionaryCatalog.RELEASE_TAG)
        assertEquals(4, PinyinDictionaryCatalog.entries.size)
        PinyinDictionaryCatalog.entries.forEach { entry ->
            assertTrue(entry.url.startsWith("https://"))
            assertEquals(64, entry.sha256.length)
            assertEquals(40, entry.sourceRevision.length)
            if (entry.sourceInputSha256 != null) assertEquals(64, entry.sourceInputSha256.length)
            assertTrue(entry.size > 0)
            assertTrue(entry.entryCount == null || entry.entryCount > 0)
            assertTrue(entry.license.isNotBlank())
        }
    }

    @Test
    fun releaseEntriesMatchPublishedReleaseIndex() {
        assertEquals(37_322_174L, PinyinDictionaryCatalog.find("rime-frost")!!.size)
        assertEquals(
            "b4880861161d585b21413fe554aa8f416beb39d68cf4ce3fba728df5fea584ff",
            PinyinDictionaryCatalog.find("rime-frost")!!.sha256
        )
        assertEquals(2_010_605L, PinyinDictionaryCatalog.find("rime-frost")!!.entryCount)
        assertEquals(24_683_718L, PinyinDictionaryCatalog.find("rime-wanxiang")!!.size)
        assertEquals(
            "492a452604f1d63ec1edf5682846291db72f3caadc3b6cc8e51af52fab3772da",
            PinyinDictionaryCatalog.find("rime-wanxiang")!!.sha256
        )
        assertEquals(1_425_249L, PinyinDictionaryCatalog.find("rime-wanxiang")!!.entryCount)
    }

    @Test
    fun catalogKeepsLocalizedAndCanonicalNames() {
        val frost = PinyinDictionaryCatalog.find("rime-frost")
        val wanxiang = PinyinDictionaryCatalog.find("rime-wanxiang")

        assertNotNull(frost)
        assertNotNull(wanxiang)
        assertEquals("白霜", frost!!.displayName)
        assertEquals("Rime-Frost", frost.canonicalName)
        assertEquals("万象", wanxiang!!.displayName)
        assertEquals("Rime-Wanxiang (jichu)", wanxiang.canonicalName)
    }

    @Test
    fun researchEntriesCarryPinnedBytesAndAreNotPublicReleaseApproved() {
        val zhwiki = PinyinDictionaryCatalog.find("zhwiki")!!
        assertEquals(32_677_637L, zhwiki.size)
        assertEquals(1_673_006L, zhwiki.entryCount)
        assertTrue(zhwiki.researchOnly)
        assertTrue(!zhwiki.publicReleaseApproved)
        assertEquals("中文维基", zhwiki.displayName)

        val custom = PinyinDictionaryCatalog.find("custom-pinyin")!!
        assertEquals(29_688_969L, custom.size)
        assertEquals(1_498_781L, custom.entryCount)
        assertTrue(custom.researchOnly)
        assertTrue(!custom.publicReleaseApproved)
        assertEquals("CustomPinyinDictionary", custom.canonicalName)

    }
}
