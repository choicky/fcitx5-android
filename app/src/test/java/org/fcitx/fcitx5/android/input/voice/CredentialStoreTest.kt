/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStoreTest {

    /** The same AES-GCM construction as the Keystore cipher, with a software key. */
    private class SoftwareCipher(private val key: SecretKey) : SecretCipher {
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return iv + cipher.doFinal(plain)
        }

        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
            cipher.updateAAD(aad)
            return cipher.doFinal(blob, 12, blob.size - 12)
        }
    }

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val dir = Files.createTempDirectory("creds").toFile()
    private val cipher = SoftwareCipher(key())
    private val store = CredentialStore(dir, cipher)

    @Test
    fun roundTripPerProvider() {
        store.write("doubao", mapOf("api_key" to "k-123", "resource_id" to "volc.seedasr.sauc.duration"))
        store.write("tencent", mapOf("secret_id" to "id", "secret_key" to "s"))
        assertEquals("k-123", store.read("doubao")!!["api_key"])
        assertEquals("s", store.read("tencent")!!["secret_key"])
        store.clear("doubao")
        assertNull(store.read("doubao"))
        assertEquals("id", store.read("tencent")!!["secret_id"])
    }

    @Test
    fun secretsAreNotStoredInPlainText() {
        store.write("doubao", mapOf("api_key" to "PLAINTEXT-SECRET-VALUE"))
        val raw = dir.resolve("doubao.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("PLAINTEXT-SECRET-VALUE"))
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun aBlobCannotBeMovedToAnotherProvider() {
        store.write("doubao", mapOf("api_key" to "k"))
        dir.resolve("doubao.bin").copyTo(dir.resolve("qwen.bin"))
        assertNull(store.read("qwen"))
    }

    @Test
    fun anUnreadableBlobIsTreatedAsNotConfigured() {
        store.write("doubao", mapOf("api_key" to "k"))
        // e.g. restored to another device where the Keystore key does not exist
        assertNull(CredentialStore(dir, SoftwareCipher(key())).read("doubao"))
        dir.resolve("doubao.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.read("doubao"))
    }

    @Test
    fun providerIdsCannotEscapeTheDirectory() {
        assertTrue(runCatching { store.write("../x", emptyMap()) }.isFailure)
    }

    @Test
    fun masking() {
        assertNull(maskedSecret(""))
        assertNull(maskedSecret(null))
        assertFalse(maskedSecret("abcdef")!!.contains("abc"))
    }
}
