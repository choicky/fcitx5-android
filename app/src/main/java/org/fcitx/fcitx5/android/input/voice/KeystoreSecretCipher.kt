/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key. The key never leaves the device and
 * is not part of backups, so a restored blob cannot be decrypted elsewhere and simply reads as
 * "not configured".
 */
internal object KeystoreSecretCipher : SecretCipher {
    private const val ALIAS = "fcitx5_voice_credentials_v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // the Keystore picks a random IV
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plain)
        check(cipher.iv.size == IV_BYTES)
        return cipher.iv + body
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    /** The per-app credential store; see [CredentialStore]. */
    fun store(context: Context) =
        CredentialStore(context.noBackupFilesDir.resolve("asr-credentials"), this)
}
