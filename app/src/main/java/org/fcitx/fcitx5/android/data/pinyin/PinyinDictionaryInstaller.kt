/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal sealed class DictionaryInstallFailure(message: String) : Exception(message) {
    class Cancelled : DictionaryInstallFailure("cancelled")
    class NotEnoughSpace(val needed: Long, val available: Long) :
        DictionaryInstallFailure("needs $needed bytes, $available available")
    class Mismatch(val expected: String, val actual: String) :
        DictionaryInstallFailure("dictionary SHA-256 mismatch")
}

/** Installs one verified `.dict` artifact without touching the active file until verification. */
internal class PinyinDictionaryInstaller(private val root: File) {

    fun target(entry: PinyinDictionaryCatalogEntry) = root.resolve(entry.fileName)

    fun isInstalled(entry: PinyinDictionaryCatalogEntry): Boolean =
        target(entry).let { it.isFile && it.length() == entry.size && sha256(it) == entry.sha256 }

    fun install(
        entry: PinyinDictionaryCatalogEntry,
        source: InputStream,
        cancelled: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        root.mkdirs()
        val available = root.usableSpace
        if (available < entry.size) {
            throw DictionaryInstallFailure.NotEnoughSpace(entry.size, available)
        }
        val staged = root.resolve(".${entry.fileName}.download")
        try {
            staged.outputStream().use { output ->
                copyVerified(entry, source, output, cancelled, progress)
            }
            val old = root.resolve(".${entry.fileName}.old")
            old.delete()
            val destination = target(entry)
            if (destination.exists() && !destination.renameTo(old)) {
                throw IOException("cannot preserve ${destination.name}")
            }
            if (!staged.renameTo(destination)) {
                old.renameTo(destination)
                throw IOException("cannot install ${destination.name}")
            }
            old.delete()
            return destination
        } catch (e: DictionaryInstallFailure) {
            staged.delete()
            throw e
        }
    }

    fun remove(entry: PinyinDictionaryCatalogEntry) {
        val destination = target(entry)
        if (!destination.exists()) return
        val trash = destination.resolveSibling(".${entry.fileName}.trash")
        trash.delete()
        if (destination.renameTo(trash)) trash.delete() else destination.delete()
    }

    private fun copyVerified(
        entry: PinyinDictionaryCatalogEntry,
        source: InputStream,
        output: OutputStream,
        cancelled: () -> Boolean,
        progress: (Long, Long) -> Unit
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            if (cancelled()) throw DictionaryInstallFailure.Cancelled()
            val count = source.read(buffer)
            if (count < 0) break
            copied += count
            if (copied > entry.size) {
                throw DictionaryInstallFailure.Mismatch(entry.sha256, "oversized")
            }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            progress(copied, entry.size)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (copied != entry.size || actual != entry.sha256) {
            throw DictionaryInstallFailure.Mismatch(entry.sha256, actual)
        }
    }

    companion object {
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").apply {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    update(buffer, 0, count)
                }
            }
        }.digest().joinToString("") { "%02x".format(it) }
    }
}
