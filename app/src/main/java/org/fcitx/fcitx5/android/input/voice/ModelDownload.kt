/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

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

    fun downloadArchive(
        client: OkHttpClient,
        url: String,
        target: File,
        expectedSize: Long,
        expectedSha256: String,
        allowCleartext: Boolean = false,
        onCall: (Call) -> Unit = {},
        cancelled: () -> Boolean = { false },
        paused: () -> Boolean = { false },
        downloadPhase: (Boolean) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        require(modelSourceProblem(url, allowCleartext) == null) { "unsupported model source" }
        target.parentFile!!.mkdirs()
        var from = target.takeIf { it.isFile }?.length() ?: 0L
        if (from > expectedSize) {
            target.delete()
            from = 0
        }
        downloadPhase(true)
        var response = client.newCall(Request.Builder().url(url).apply {
            if (from > 0) header("Range", "bytes=$from-")
        }.build()).also(onCall).execute()
        val resumed = from > 0 && response.code == 206 &&
            response.header("Content-Range")?.startsWith("bytes $from-") == true
        if (from > 0 && !resumed && (response.code == 206 || response.code == 416)) {
            response.close()
            from = 0
            response = client.newCall(Request.Builder().url(url).build()).also(onCall).execute()
        }
        if (response.code !in listOf(200, 206) || response.body == null) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        val append = resumed
        var done = if (append) from else 0L
        if (!append && target.exists()) target.delete()
        try {
            response.body!!.byteStream().use { input ->
                FileOutputStream(target, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    onProgress(done, expectedSize)
                    while (true) {
                        if (cancelled()) throw InstallFailure.Cancelled()
                        if (paused()) throw InstallFailure.Paused()
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (done > expectedSize) throw InstallFailure.Mismatch("archive")
                        output.write(buffer, 0, n)
                        onProgress(done, expectedSize)
                    }
                }
            }
        } catch (e: IOException) {
            if (cancelled()) throw InstallFailure.Cancelled()
            if (paused()) throw InstallFailure.Paused()
            throw e
        } finally {
            response.close()
        }
        if (done != expectedSize) throw InstallFailure.Mismatch("archive")
        downloadPhase(false)
        val digest = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancelled()) throw InstallFailure.Cancelled()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != expectedSha256) {
            target.delete()
            throw InstallFailure.Mismatch("archive")
        }
    }

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
