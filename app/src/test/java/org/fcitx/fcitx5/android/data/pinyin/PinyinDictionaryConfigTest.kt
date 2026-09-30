/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinDictionaryConfigTest {
    @Test
    fun extBUsesTheExistingPinyinConfigOption() {
        val config = RawConfig(
            "pinyin", arrayOf(
                RawConfig("cfg", arrayOf(RawConfig("ExtBEnabled", false)))
            )
        )

        assertFalse(PinyinDictionaryConfig.extBEnabled(config))
        PinyinDictionaryConfig.setExtBEnabled(config, true)
        assertTrue(PinyinDictionaryConfig.extBEnabled(config))
        assertEquals("True", config["cfg"]["ExtBEnabled"].value)
    }

    @Test
    fun missingOptionIsNotInventedAsEnabled() {
        assertFalse(PinyinDictionaryConfig.extBEnabled(RawConfig("pinyin", arrayOf())))
    }
}
