/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** One pinned file of a Local model: its path inside the model directory, size and SHA-256. */
internal data class ModelFile(val path: String, val size: Long, val sha256: String)

/**
 * The Model Manager catalog (D037). Both entries are research models; neither is a formal or
 * recommended model (D036). [downloadBase] is set only where the licence evidence allows the
 * app to offer a download; files are fetched from that pinned upstream revision, never mirrored.
 */
internal data class ModelCatalogEntry(
    val model: LocalAsrModel,
    val version: String,
    val files: List<ModelFile>,
    val downloadBase: String?
) {
    val totalBytes get() = files.sumOf { it.size }

    fun downloadUrl(file: ModelFile) = downloadBase?.let { "$it/${file.path}" }

    companion object {
        /**
         * A: licence of the weights is not declared anywhere upstream, so import only.
         * Hashes: HF csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30 @ ad658fa0.
         */
        val ZipformerZh = ModelCatalogEntry(
            LocalAsrModel.ZipformerZh,
            version = "2025-06-30 (HF ad658fa0)",
            files = listOf(
                ModelFile("encoder.int8.onnx", 161141793, "5ac51e27981bb4dab01bb9be4958453ba50c3b61c063ddda0eab23fd3671aa4f"),
                ModelFile("decoder.onnx", 5165083, "06522ad63cec0fdf6809f4e1db9bb4f7d710c34582e3b35db62ac60eccafac7e"),
                ModelFile("joiner.int8.onnx", 1033416, "b34584dc6f561089e1d747fedebb3765f2caa72c927ef54d7ca55e5ae40a814b"),
                ModelFile("tokens.txt", 20628, "6193c7ea1c96d0d9a1e9652789b40d13a8a913b434a5451e93158f5a09fd6652")
            ),
            downloadBase = null
        )

        /**
         * B: Apache-2.0 declared for Fun-ASR-Nano, the ONNX export (ModelScope metadata) and
         * Qwen3-0.6B; downloaded from the pinned Hugging Face revision 6f16bd37.
         */
        val FunAsrNano = ModelCatalogEntry(
            LocalAsrModel.FunAsrNano,
            version = "2025-12-30 (HF 6f16bd37)",
            files = listOf(
                ModelFile("encoder_adaptor.int8.onnx", 237792748, "f36dea2e30fbc33b5db1d7a7265cc976c5e5586c77b042d5adb1ad27c72db422"),
                ModelFile("llm.int8.onnx", 600356593, "dfbf9aa3be41bccc257587f151e15c63fbe1b549f2b517f5ccd5bdce3bf4322a"),
                ModelFile("embedding.int8.onnx", 155584380, "95e61cd0c9c3b9543339a4cf973c95c116815e745ccc1e0285cbd81f76d18644"),
                ModelFile("Qwen3-0.6B/vocab.json", 2776833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
                ModelFile("Qwen3-0.6B/merges.txt", 1671853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
                ModelFile("Qwen3-0.6B/tokenizer.json", 11422654, "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4")
            ),
            downloadBase = "https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30/resolve/6f16bd378457e13f36ccf3910df9017f96c346fb"
        )

        val entries = listOf(ZipformerZh, FunAsrNano)

        fun of(model: LocalAsrModel) = entries.first { it.model == model }
    }
}

/** Why an installation stopped; nothing is left in the model directory in any of these cases. */
internal sealed class InstallFailure(message: String) : Exception(message) {
    class Cancelled : InstallFailure("cancelled")
    class NotEnoughSpace(val needed: Long, val available: Long) :
        InstallFailure("needs $needed bytes, $available available")
    class Missing(val path: String) : InstallFailure("missing $path")
    class Mismatch(val path: String) : InstallFailure("size or SHA-256 mismatch: $path")
    class Io(val path: String, cause: IOException) : InstallFailure("$path: ${cause.message}")
}

/**
 * Installs a catalog entry into `root/<dirName>` from any source (download or import):
 * every file goes to a temporary directory and is checked against its pinned size and
 * SHA-256; only a complete, verified set replaces the model directory, by rename. A failed,
 * cancelled or interrupted install never leaves a partial model behind.
 */
internal class LocalModelInstaller(private val root: File) {

    fun modelDir(model: LocalAsrModel) = root.resolve(model.dirName)

    fun isInstalled(model: LocalAsrModel) = model.missingFiles(modelDir(model)).isEmpty()

    /**
     * [open] returns the stream for one file, or null if the source does not have it.
     * [onProgress] gets bytes done / total; [cancelled] is polled between reads.
     * [attempts] retries one file that fails with an I/O error or a hash mismatch.
     */
    fun install(
        entry: ModelCatalogEntry,
        open: (ModelFile) -> InputStream?,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        attempts: Int = 1
    ) {
        root.mkdirs()
        cleanStale(entry.model)
        val needed = entry.totalBytes
        val available = root.usableSpace
        // room for the new copy while an old one may still exist
        if (available < needed + needed / 20) throw InstallFailure.NotEnoughSpace(needed, available)
        val tmp = root.resolve(".tmp-${entry.model.dirName}")
        tmp.deleteRecursively()
        try {
            var done = 0L
            for (file in entry.files) {
                var attempt = 0
                while (true) {
                    try {
                        copyVerified(file, open, tmp, cancelled) { n -> onProgress(done + n, needed) }
                        break
                    } catch (e: InstallFailure) {
                        if (e is InstallFailure.Cancelled || e is InstallFailure.Missing) throw e
                        if (++attempt >= attempts) throw e
                    }
                }
                done += file.size
                onProgress(done, needed)
            }
            swapIn(tmp, modelDir(entry.model))
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun copyVerified(
        file: ModelFile,
        open: (ModelFile) -> InputStream?,
        tmp: File,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit
    ) {
        val target = tmp.resolve(file.path)
        require(target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) { "bad path" }
        target.parentFile!!.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val input = open(file) ?: throw InstallFailure.Missing(file.path)
            input.use { src ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw InstallFailure.Cancelled()
                        val n = src.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > file.size) throw InstallFailure.Mismatch(file.path)
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        progress(copied)
                    }
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw InstallFailure.Io(file.path, e)
        } catch (e: InstallFailure) {
            target.delete()
            throw e
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (copied != file.size || hash != file.sha256) {
            target.delete()
            throw InstallFailure.Mismatch(file.path)
        }
    }

    /** Old directory out, verified one in; a session still using the old files keeps them open. */
    private fun swapIn(tmp: File, dest: File) {
        val old = root.resolve(".old-${dest.name}")
        old.deleteRecursively()
        if (dest.exists() && !dest.renameTo(old)) throw IOException("cannot replace ${dest.name}")
        if (!tmp.renameTo(dest)) {
            old.renameTo(dest)
            throw IOException("cannot install ${dest.name}")
        }
        old.deleteRecursively()
    }

    /**
     * Removal is a rename first, so the model disappears atomically for new sessions; a
     * session that already loaded it keeps its open files until it ends.
     */
    fun remove(model: LocalAsrModel) {
        val dir = modelDir(model)
        if (!dir.exists()) return
        val trash = root.resolve(".trash-${model.dirName}-${System.nanoTime()}")
        if (dir.renameTo(trash)) trash.deleteRecursively() else dir.deleteRecursively()
    }

    /**
     * Leftovers of an interrupted install or removal (for example after the process died). An
     * `.old` copy without a model directory is the last good install and is put back.
     */
    fun cleanStale(model: LocalAsrModel) {
        val dest = modelDir(model)
        val old = root.resolve(".old-${model.dirName}")
        if (old.exists() && !dest.exists()) old.renameTo(dest)
        root.listFiles()?.filter {
            it.name == ".tmp-${model.dirName}" || it.name == ".old-${model.dirName}" ||
                    it.name.startsWith(".trash-${model.dirName}-")
        }?.forEach { it.deleteRecursively() }
    }
}
