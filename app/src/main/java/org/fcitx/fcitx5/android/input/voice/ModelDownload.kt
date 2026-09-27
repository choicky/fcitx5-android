/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Model file sources for [LocalModelInstaller]. Downloads use HTTPS from the catalog's pinned
 * upstream revision; whatever arrives is still checked against the pinned SHA-256.
 */
internal object ModelSources {

    /** Only for entries whose licence evidence allows an app-offered download. */
    fun download(client: OkHttpClient, entry: ModelCatalogEntry): (ModelFile) -> InputStream? {
        requireNotNull(entry.downloadBase) { "${entry.model} is not offered for download" }
        return { file ->
            val url = entry.downloadUrl(file)!!
            require(url.startsWith("https://")) { "downloads must use HTTPS" }
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("HTTP ${response.code}")
            }
            response.body!!.byteStream()
        }
    }

    /**
     * Import from a directory the user provides (for example a model they obtained lawfully);
     * files are matched by their path inside the model directory.
     */
    fun directory(dir: File): (ModelFile) -> InputStream? = { file ->
        dir.resolve(file.path).takeIf { it.isFile }?.inputStream()
    }
}
