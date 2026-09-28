/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * Why a model source address cannot be used: files are fetched as `<base>/<path>` over HTTPS;
 * plain HTTP only in debug builds (for a computer on the local network).
 */
internal fun modelSourceProblem(base: String, allowCleartext: Boolean): EndpointProblem? {
    val uri = runCatching { URI(base.trim()) }.getOrNull() ?: return EndpointProblem.Invalid
    if (uri.host.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return EndpointProblem.Invalid
    return when (uri.scheme?.lowercase()) {
        "https" -> null
        "http" -> if (allowCleartext) null else EndpointProblem.Cleartext
        else -> EndpointProblem.Invalid
    }
}

/**
 * Model file sources for [LocalModelInstaller]. Whatever arrives is checked against the pinned
 * SHA-256, whichever address it came from.
 */
internal object ModelSources {

    /**
     * Files from [base]: the catalog's pinned upstream revision, or an address the user entered
     * (for example a mirror of that revision). A staged partial file resumes with an HTTP Range
     * request; a server that ignores it sends the whole file again.
     */
    fun download(
        client: OkHttpClient,
        entry: ModelCatalogEntry,
        base: String? = entry.downloadBase,
        allowCleartext: Boolean = false,
        /** Sees every call before it runs, e.g. to cancel it with its task. */
        onCall: (Call) -> Unit = {}
    ): ModelFileSource {
        requireNotNull(base) { "${entry.model} has no download source" }
        require(modelSourceProblem(base, allowCleartext) == null) { "unsupported model source" }
        val trimmed = base.trim()
        fun fetch(file: ModelFile, from: Long) = client.newCall(
            Request.Builder().url(entry.downloadUrl(file, trimmed)!!).apply {
                if (from > 0) header("Range", "bytes=$from-")
            }.build()
        ).also(onCall).execute()
        return { file, resumeFrom ->
            var response = fetch(file, resumeFrom)
            val resumed = resumeFrom > 0 && response.code == 206 &&
                    response.header("Content-Range")?.startsWith("bytes $resumeFrom-") == true
            // a range the server cannot serve as asked: the whole file instead
            if (resumeFrom > 0 && !resumed && (response.code == 206 || response.code == 416)) {
                response.close()
                response = fetch(file, 0)
            }
            when {
                resumed -> ModelStream(response.body!!.byteStream(), resumeFrom)
                response.code == 200 -> ModelStream(response.body!!.byteStream(), 0)
                else -> {
                    response.close()
                    throw IOException("HTTP ${response.code}")
                }
            }
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
