/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PinyinDictionaryCatalogTest {
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
}
