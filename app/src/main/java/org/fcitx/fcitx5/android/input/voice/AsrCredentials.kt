/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Authenticated encryption for credential blobs; [aad] binds a blob to its owner. */
internal interface SecretCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray

    /** Throws if the blob was changed, belongs to another owner, or the key is gone. */
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * User-owned ASR credentials (Direct BYOK, D028), one encrypted file per provider so no
 * provider can read or replace another's. Stored outside SharedPreferences, in a directory that
 * is neither backed up nor included in the user-data export; ASR credentials are independent of
 * any future LLM credentials. Values are never logged.
 */
internal class CredentialStore(private val dir: File, private val cipher: SecretCipher) {

    private fun file(provider: String): File {
        require(provider.matches(Regex("[a-z0-9_.-]+"))) { "bad provider id" }
        return dir.resolve("$provider.bin")
    }

    /**
     * The stored fields, or null when none are stored or they cannot be decrypted (for example
     * after a restore to another device, where the Keystore key does not exist).
     */
    fun read(provider: String): Map<String, String>? {
        val f = FileReplace.recover(file(provider))
        if (!f.isFile) return null
        return runCatching { decode(cipher.decrypt(f.readBytes(), provider.toByteArray())) }
            .getOrNull()
    }

    fun write(provider: String, fields: Map<String, String>) {
        dir.mkdirs()
        val f = file(provider)
        val tmp = File(dir, "${f.name}.tmp")
        tmp.writeBytes(cipher.encrypt(encode(fields), provider.toByteArray()))
        // on failure the previously stored credentials stay
        if (!FileReplace.replace(tmp, FileReplace.recover(f))) {
            tmp.delete()
            error("could not store credentials")
        }
    }

    /** Whether something is stored; reading it may still fail (see [read]). */
    fun has(provider: String): Boolean = FileReplace.recover(file(provider)).isFile

    fun clear(provider: String) {
        // an interrupted replacement's backup goes too, or it would come back
        FileReplace.recover(file(provider)).delete()
    }

    companion object {
        private const val VERSION = 1

        fun encode(fields: Map<String, String>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeByte(VERSION)
                out.writeInt(fields.size)
                fields.forEach { (k, v) ->
                    out.writeUTF(k)
                    out.writeUTF(v)
                }
            }
            return bytes.toByteArray()
        }

        fun decode(bytes: ByteArray): Map<String, String> =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readByte().toInt() == VERSION) { "unknown credential format" }
                val count = input.readInt()
                require(count in 0..64) { "bad credential format" }
                buildMap { repeat(count) { put(input.readUTF(), input.readUTF()) } }
            }
    }
}

/** How a secret is shown: never its value, only whether one is set. */
internal fun maskedSecret(value: String?): String? = if (value.isNullOrEmpty()) null else "••••••"
