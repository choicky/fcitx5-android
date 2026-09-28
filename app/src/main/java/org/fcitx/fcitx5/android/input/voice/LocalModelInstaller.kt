/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** One pinned file of a Local model: its path inside the model directory, size and SHA-256. */
internal data class ModelFile(val path: String, val size: Long, val sha256: String)

/**
 * The Model Manager catalog (D037). All entries are research models; none is a formal or
 * recommended model (D036). Files are fetched from the pinned upstream revision at
 * [downloadBase] (never mirrored or bundled) and always checked against the pinned SHA-256.
 */
internal data class ModelCatalogEntry(
    val model: LocalAsrModel,
    val version: String,
    val files: List<ModelFile>,
    val downloadBase: String?,
    /**
     * The download is the owner's personal-testing exception (D037, 2026-09-28): offered only
     * in test (debug) builds. It says nothing about public-release eligibility.
     */
    val testBuildDownloadOnly: Boolean = false
) {
    val totalBytes get() = files.sumOf { it.size }

    /** Whether this build offers the pinned download (and a user-supplied source for it). */
    fun downloadOffered(testBuild: Boolean) = downloadBase != null && (testBuild || !testBuildDownloadOnly)

    fun downloadUrl(file: ModelFile, base: String? = downloadBase) =
        base?.let { "${it.trimEnd('/')}/${file.path}" }

    /** Staged files of a different catalog version are not reused. */
    val stamp get() = version + "\n" + files.joinToString("\n") { "${it.path} ${it.size} ${it.sha256}" }

    /** "huggingface.co/<owner>/<repo> @ <revision>" for display; null without a download. */
    val sourceLabel: String?
        get() = downloadBase?.let {
            HF_RESOLVE.matchEntire(it)?.let { m -> "huggingface.co/${m.groupValues[1]} @ ${m.groupValues[2].take(8)}" } ?: it
        }

    companion object {
        /**
         * A: no licence is declared for the weights, either on this conversion (public, not
         * gated, no licence metadata) or on the icefall checkpoint it was converted from
         * (yuekai/icefall-asr-multi-zh-hans-zipformer-large, gated: access after agreeing to
         * share contact information). Downloadable only in test builds, for the owner's personal
         * testing; not cleared for public release.
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
            downloadBase = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30/resolve/ad658fa0201659a09ea3c176129a191c77ecae8f",
            testBuildDownloadOnly = true
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

        /**
         * C: Apache-2.0 on the sherpa-onnx mirror and on the upstream
         * pfluo/k2fsa-zipformer-chinese-english-mixed; training data not published.
         * Hashes: HF csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20 @ 98590b7e.
         */
        val ZipformerBilingual = ModelCatalogEntry(
            LocalAsrModel.ZipformerBilingual,
            version = "2023-02-20 (HF 98590b7e)",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 181895032, "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b"),
                ModelFile("decoder-epoch-99-avg-1.onnx", 13876452, "2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d"),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 3228404, "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0"),
                ModelFile("tokens.txt", 56317, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3")
            ),
            downloadBase = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/98590b7ed6443e77b714204da2757d75e1a642f4"
        )

        /** User-facing catalog. ZipformerZh remains above as historical metadata only. */
        val entries = listOf(ZipformerBilingual, FunAsrNano)

        private val HF_RESOLVE = Regex("""https://huggingface\.co/([^/]+/[^/]+)/resolve/([0-9a-f]{40})/?""")

        fun of(model: LocalAsrModel) = entries.first { it.model == model }
    }
}

/** Why an installation stopped; nothing incomplete is ever installed in any of these cases. */
internal sealed class InstallFailure(message: String) : Exception(message) {
    class Cancelled : InstallFailure("cancelled")
    class NotEnoughSpace(val needed: Long, val available: Long) :
        InstallFailure("needs $needed bytes, $available available")
    class Missing(val path: String) : InstallFailure("missing $path")
    class Mismatch(val path: String) : InstallFailure("size or SHA-256 mismatch: $path")
    class Io(val path: String, cause: IOException) : InstallFailure("$path: ${cause.message}")
}

/** A file's bytes from [offset] on; a source that cannot resume starts at 0. */
internal class ModelStream(val input: InputStream, val offset: Long = 0)

/** Opens one file of a model, resuming at the given offset where the source can. */
internal typealias ModelFileSource = (file: ModelFile, resumeFrom: Long) -> ModelStream?

/**
 * Installs a catalog entry into `root/<dirName>` from any source (download or import):
 * every file goes to a staging directory and is checked against its pinned size and SHA-256;
 * only a complete, verified set replaces the model directory, by rename. Staged files are never
 * used for recognition. After a network or checksum failure they are kept, so a retry resumes
 * (a completed file is hashed again before it is reused); a cancellation removes them.
 */
internal class LocalModelInstaller(private val root: File) {

    fun modelDir(model: LocalAsrModel) = root.resolve(model.dirName)

    private fun stagingDir(model: LocalAsrModel) = root.resolve(".tmp-${model.dirName}")

    fun isInstalled(model: LocalAsrModel) = model.missingFiles(modelDir(model)).isEmpty()

    /**
     * The directory recognition uses: the Model Manager copy, else a complete copy at the
     * [legacy] location of the earlier adb workflow, else the (incomplete) Model Manager path.
     */
    fun activeDir(model: LocalAsrModel, legacy: File?): File {
        val installed = modelDir(model)
        if (model.missingFiles(installed).isEmpty()) return installed
        if (legacy != null && model.missingFiles(legacy).isEmpty()) return legacy
        return installed
    }

    /** Bytes staged by an unfinished install, which a retry can reuse; 0 if none. */
    fun stagedBytes(model: LocalAsrModel): Long = stagingDir(model).walkTopDown()
        .filter { it.isFile && it.name != STAMP }.sumOf { it.length() }

    /** Import sources and tests: whole files only. */
    fun install(
        entry: ModelCatalogEntry,
        open: (ModelFile) -> InputStream?,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        attempts: Int = 1
    ) = install(entry, { file: ModelFile, _: Long -> open(file)?.let { ModelStream(it) } }, onProgress, cancelled, attempts)

    /**
     * [source] returns the stream for one file, or null if the source does not have it.
     * [onProgress] gets bytes done / total; [cancelled] is polled between reads.
     * [attempts] retries one file that fails with an I/O error or a hash mismatch.
     */
    fun install(
        entry: ModelCatalogEntry,
        source: ModelFileSource,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        attempts: Int = 1
    ) {
        root.mkdirs()
        cleanStale(entry.model)
        val tmp = stagingDir(entry.model)
        val stamp = tmp.resolve(STAMP)
        // staged files of another catalog version are not reused
        if (tmp.exists() && runCatching { stamp.readText() }.getOrNull() != entry.stamp) tmp.deleteRecursively()
        val needed = entry.totalBytes
        val remaining = (needed - stagedBytes(entry.model)).coerceAtLeast(0)
        val available = root.usableSpace
        // room for the rest of the new copy, with a margin, while an old one may still exist
        if (available < remaining + needed / 20) throw InstallFailure.NotEnoughSpace(remaining, available)
        tmp.mkdirs()
        stamp.writeText(entry.stamp)
        var keepStaged = false
        try {
            var done = 0L
            for (file in entry.files) {
                var attempt = 0
                while (true) {
                    try {
                        copyVerified(file, source, tmp, cancelled) { n -> onProgress(done + n, needed) }
                        break
                    } catch (e: InstallFailure) {
                        if (e is InstallFailure.Cancelled || e is InstallFailure.Missing || ++attempt >= attempts) {
                            // verified files stay for a retry, unless the user cancelled
                            keepStaged = e !is InstallFailure.Cancelled
                            throw e
                        }
                    }
                }
                done += file.size
                onProgress(done, needed)
            }
            stamp.delete()
            swapIn(tmp, modelDir(entry.model))
        } finally {
            if (!keepStaged) tmp.deleteRecursively()
        }
    }

    private fun copyVerified(
        file: ModelFile,
        source: ModelFileSource,
        tmp: File,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit
    ) {
        val target = tmp.resolve(file.path)
        require(target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) { "bad path" }
        target.parentFile!!.mkdirs()
        var have = if (target.isFile) target.length() else 0L
        if (have == file.size) {
            // completed by an earlier attempt: reused only if it still verifies
            if (runCatching { sha256(target) }.getOrNull() == file.sha256) {
                progress(have)
                return
            }
            have = 0
        }
        if (have > file.size) have = 0
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            val stream = source(file, have) ?: throw InstallFailure.Missing(file.path)
            stream.input.use { src ->
                // append only when the source really continues where the staged bytes end
                val append = have > 0 && stream.offset == have
                if (append) target.inputStream().use { digest.update(it) } else have = 0
                copied = have
                progress(copied)
                FileOutputStream(target, append).use { out ->
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
            // a cancelled download fails its blocking read on purpose
            if (cancelled()) {
                target.delete()
                throw InstallFailure.Cancelled()
            }
            // the bytes written so far stay staged, so a retry can resume after them
            throw InstallFailure.Io(file.path, e)
        } catch (e: InstallFailure) {
            if (e !is InstallFailure.Missing) target.delete()
            throw e
        }
        val hash = digest.digest().hex()
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
     * Removes the installed copy, any staged partial download and a copy at the [legacy]
     * location of the earlier adb workflow, which recognition would otherwise keep using.
     * Each directory is renamed first, so the model disappears atomically for new sessions;
     * a session that already loaded it keeps its open files until it ends.
     */
    fun remove(model: LocalAsrModel, legacy: File? = null) {
        listOfNotNull(modelDir(model), stagingDir(model), legacy).forEach { dir ->
            if (!dir.exists()) return@forEach
            val trash = dir.resolveSibling(".trash-${model.dirName}-${System.nanoTime()}")
            if (dir.renameTo(trash)) trash.deleteRecursively() else dir.deleteRecursively()
        }
    }

    /**
     * Leftovers of an interrupted install or removal (for example after the process died). An
     * `.old` copy without a model directory is the last good install and is put back. Staged
     * files stay for a retry.
     */
    fun cleanStale(model: LocalAsrModel) {
        val dest = modelDir(model)
        val old = root.resolve(".old-${model.dirName}")
        if (old.exists() && !dest.exists()) old.renameTo(dest)
        root.listFiles()?.filter {
            it.name == ".old-${model.dirName}" || it.name.startsWith(".trash-${model.dirName}-")
        }?.forEach { it.deleteRecursively() }
    }

    companion object {
        /** Identifies the catalog version that staged the files. */
        private const val STAMP = ".catalog"

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

        private fun MessageDigest.update(input: InputStream) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                update(buffer, 0, n)
            }
        }

        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").apply {
            file.inputStream().use { update(it) }
        }.digest().hex()
    }
}
