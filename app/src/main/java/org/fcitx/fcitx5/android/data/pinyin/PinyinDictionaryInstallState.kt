/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import java.io.File
import java.security.MessageDigest

/** What this app's catalog believes about one installed catalog dictionary. */
internal data class DictionaryInstallRecord(
    val catalogTag: String,
    val version: String,
    val sha256: String
)

/**
 * Phase 5D installed-catalog-version identity.
 *
 * Ground truth stays on disk: the `<id>.dict` / `<id>.dict.disable` files. This store
 * records which catalog release each installed artifact came from, so a later app
 * update (whose compile-time catalog may carry a newer artifact for the same id) can
 * detect that an update is available. The record's sha256 is the content identity;
 * `catalogTag`/`version` are provenance. Written as a tab-separated file inside the
 * dictionary directory, atomically (temp file + rename), and deliberately dependency
 * free so the logic is JVM-testable.
 *
 * It is not a remote catalog, not an update history, and not a general resource
 * manager: unknown ids and malformed lines are ignored, and a missing file simply
 * means "no version identity recorded yet" (see [migrate]).
 */
internal class PinyinDictionaryInstallState(private val root: File) {

    private val stateFile = File(root, STATE_FILE_NAME)

    fun load(): Map<String, DictionaryInstallRecord> {
        val text = runCatching { stateFile.readText(Charsets.UTF_8) }.getOrNull()
            ?: return emptyMap()
        val records = LinkedHashMap<String, DictionaryInstallRecord>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val fields = line.split('\t')
            if (fields.size != 4) continue
            val (id, tag, version, sha) = fields
            if (id.isEmpty() || !sha.matches(SHA_PATTERN)) continue
            records[id] = DictionaryInstallRecord(tag, version, sha)
        }
        return records
    }

    private fun save(records: Map<String, DictionaryInstallRecord>) {
        root.mkdirs()
        val builder = StringBuilder()
        builder.append("# id\tcatalog-tag\tversion\tsha256\n")
        for ((id, record) in records.toSortedMap()) {
            builder.append(id).append('\t')
                .append(record.catalogTag).append('\t')
                .append(record.version).append('\t')
                .append(record.sha256).append('\n')
        }
        val temp = File(root, "$STATE_FILE_NAME.tmp")
        temp.delete()
        temp.writeText(builder.toString(), Charsets.UTF_8)
        val backup = File(root, "$STATE_FILE_NAME.old")
        backup.delete()
        if (stateFile.exists() && !stateFile.renameTo(backup)) {
            temp.delete()
            return
        }
        if (!temp.renameTo(stateFile)) {
            temp.delete()
            backup.renameTo(stateFile)
            return
        }
        backup.delete()
    }

    fun record(entry: PinyinDictionaryCatalogEntry): DictionaryInstallRecord {
        val updated = LinkedHashMap(load())
        val value = DictionaryInstallRecord(
            PinyinDictionaryCatalog.RELEASE_TAG, entry.version, entry.sha256
        )
        updated[entry.id] = value
        save(updated)
        return value
    }

    fun forget(id: String) {
        val current = load()
        if (current.containsKey(id)) {
            val updated = LinkedHashMap(current)
            updated.remove(id)
            save(updated)
        }
    }

    /**
     * Migration from pre-5D installed state: a catalog-named file whose bytes already
     * match the current catalog artifact gains a current-version record; anything else
     * stays Untracked (user data is never rewritten or discarded here).
     */
    fun migrate(catalog: List<PinyinDictionaryCatalogEntry>): Boolean {
        var changed = false
        val current = LinkedHashMap(load())
        for (entry in catalog) {
            if (current.containsKey(entry.id)) continue
            val file = activeFile(root, entry) ?: continue
            if (!file.isFile || file.length() != entry.size) continue
            if (sha256(file) != entry.sha256) continue
            current[entry.id] = DictionaryInstallRecord(
                PinyinDictionaryCatalog.RELEASE_TAG, entry.version, entry.sha256
            )
            changed = true
        }
        if (changed) save(current)
        return changed
    }

    /**
     * Interrupted-update recovery. `swapIn` leaves a `.<name>.old` backup only when a
     * replacement was interrupted; recover it:
     *  - active dictionary missing + backup present -> rename the backup back;
     *  - active dictionary already equals the catalog artifact -> drop the stale backup;
     *  - anything ambiguous -> keep both files untouched (no silent data loss).
     * Staged `.download` partials are intentionally left in place for resume.
     */
    fun recover(catalog: List<PinyinDictionaryCatalogEntry>): List<String> {
        var recovered = listOf<String>()
        for (entry in catalog) {
            val old = File(root, ".${entry.fileName}.old")
            if (!old.isFile) continue
            val active = activeFile(root, entry)
            if (active == null) {
                if (old.renameTo(File(root, entry.fileName))) {
                    recovered += entry.id
                }
            } else if (active.length() == entry.size && sha256(active) == entry.sha256) {
                old.delete()
            }
        }
        return recovered
    }

    companion object {
        const val STATE_FILE_NAME = "catalog_state.tsv"

        private val SHA_PATTERN = Regex("^[0-9a-f]{64}$")

        /** The live file for a catalog entry: enabled name, else the disable suffix. */
        fun activeFile(root: File, entry: PinyinDictionaryCatalogEntry): File? {
            val plain = File(root, entry.fileName)
            if (plain.isFile) return plain
            val disabled = File(root, "${entry.fileName}.disable")
            return if (disabled.isFile) disabled else null
        }

        private fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").let { digest ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
    }
}
