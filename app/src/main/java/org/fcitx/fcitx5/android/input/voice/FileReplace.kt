/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.io.File

/**
 * Small files that are replaced as a whole (credentials, the last voice error).
 *
 * On Android and Linux, [File.renameTo] replaces an existing target atomically. On Windows,
 * where the JVM unit tests also run, it fails when the target exists; `java.nio.file.Files.move`
 * with REPLACE_EXISTING would handle both but needs API 26 (minSdk is 23). So when the plain
 * rename fails, an existing target is moved aside, the new file moved in, and the old one put
 * back if that fails.
 */
internal object FileReplace {

    private fun backupOf(target: File) = File(target.path + ".bak")

    /**
     * Moves [source] over [target]. Returns false on failure; [target] then still holds its
     * previous content (or is still absent) and [source] is left for the caller to delete.
     */
    fun replace(source: File, target: File, rename: (File, File) -> Boolean = File::renameTo): Boolean {
        if (rename(source, target)) return true
        if (!target.exists()) return false
        val backup = backupOf(target)
        backup.delete()
        if (!rename(target, backup)) return false
        if (rename(source, target)) {
            backup.delete()
            return true
        }
        // put the previous content back; if even that fails, [recover] does it on the next read
        rename(backup, target)
        return false
    }

    /**
     * [target], after putting back the previous content if a replacement was interrupted
     * between moving it aside and moving the new file in (for example by process death).
     */
    fun recover(target: File, rename: (File, File) -> Boolean = File::renameTo): File {
        val backup = backupOf(target)
        if (backup.isFile) {
            if (!target.exists()) rename(backup, target) else backup.delete()
        }
        return target
    }
}
