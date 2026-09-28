/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The export's zip layout; free of Android dependencies so that it can be unit tested. */
internal object UserDataArchive {

    class Tree(val dir: File, val prefix: String, val excludedDirs: Set<String> = emptySet())

    /**
     * [prepare] runs before anything is read or written; if it returns false the export fails
     * and [dest] stays untouched. [extra] adds entries after the trees.
     */
    fun write(dest: OutputStream, prepare: () -> Boolean, trees: List<Tree>, extra: (ZipOutputStream) -> Unit) {
        check(prepare()) { "could not prepare user data for export" }
        ZipOutputStream(dest.buffered()).use { zipStream ->
            trees.forEach { writeFileTree(it, zipStream) }
            extra(zipStream)
        }
    }

    private fun writeFileTree(tree: Tree, dest: ZipOutputStream) {
        val srcDir = tree.dir
        dest.putNextEntry(ZipEntry("${tree.prefix}/"))
        // zip entry names always use '/', whatever File.separator is (a backslash on Windows)
        srcDir.walkTopDown().onEnter {
            it == srcDir || it.relativeTo(srcDir).invariantSeparatorsPath !in tree.excludedDirs
        }.forEach { f ->
            val related = f.relativeTo(srcDir).invariantSeparatorsPath
            if (related != "") {
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("${tree.prefix}/$related/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("${tree.prefix}/$related"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }
}
