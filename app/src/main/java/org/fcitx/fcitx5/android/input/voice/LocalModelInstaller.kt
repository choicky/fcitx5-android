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
 * The Model Manager catalog (D037). Files are fetched from the pinned upstream revision at
 * [downloadBase] (never mirrored or bundled) and always checked against the pinned SHA-256.
 */
internal data class ModelCatalogEntry(
    val model: LocalAsrModel,
    val version: String,
    val files: List<ModelFile>,
    val downloadBase: String?,
    /** Compliance metadata shown in the model details and first-download disclosure. */
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val license: String? = null,
    val licenseUrl: String? = null,
    val attribution: String? = null,
    val distributionApproved: Boolean = false,
    val limitation: String? = null,
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
         * Fun-ASR-Nano ONNX export. ModelScope declares Apache-2.0, but the exporter source
         * repository has no LICENSE, so public approval remains separately recorded.
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
            downloadBase = "https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30/resolve/6f16bd378457e13f36ccf3910df9017f96c346fb",
            sourceName = "csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30",
            sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30/tree/6f16bd378457e13f36ccf3910df9017f96c346fb",
            license = "Apache-2.0 (ModelScope metadata; exporter source has no LICENSE)",
            licenseUrl = "https://www.modelscope.cn/models/zengshuishui/FunASR-nano-onnx",
            attribution = "FunAudioLLM/Fun-ASR-Nano-2512; Wasser1462/FunASR-nano-onnx; zengshuishui ONNX export",
            limitation = "Research model; the tested export produced empty final results for about 34–39 second utterances."
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
            downloadBase = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/98590b7ed6443e77b714204da2757d75e1a642f4",
            sourceName = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            sourceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/tree/98590b7ed6443e77b714204da2757d75e1a642f4",
            license = "Apache-2.0",
            licenseUrl = "https://huggingface.co/pfluo/k2fsa-zipformer-chinese-english-mixed",
            attribution = "csukuangfj sherpa-onnx conversion; pfluo/k2fsa-zipformer-chinese-english-mixed; k2-fsa/icefall",
            limitation = "Research candidate; training-data provenance is not published in the model materials."
        )

        /** Exact files extracted from the pinned GitHub archive; import only pending distribution audit. */
        val XAsrOffline = ModelCatalogEntry(
            LocalAsrModel.XAsrOffline,
            version = "2026-06-03 (asset 460927314)",
            files = listOf(
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 161015713, "7f6aa62056efd8af9da13e0faa81cd3f284d2fb2e3b63de56fd2dfd3450910dc"),
                ModelFile("decoder-epoch-99-avg-1.onnx", 11309084, "72f47405d3c1033bebccbef82f90071e7b4ba3e71b9c986f2b74244b25723aed"),
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 2581422, "aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3"),
                ModelFile("tokens.txt", 58806, "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c")
            ),
            downloadBase = null,
            sourceName = "k2-fsa/sherpa-onnx/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03",
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-int8-2026-06-03.tar.bz2",
            license = "Apache-2.0 (author declaration; archive has no LICENSE/NOTICE)",
            licenseUrl = "https://huggingface.co/GilgameshWind/X-ASR-zh-en/blob/689ff18c584d29910da37b6fe904db0c1489c9d1/README.md",
            attribution = "Gilgamesh-J/X-ASR; Fangjun Kuang / Xiaomi sherpa-onnx export; k2-fsa/icefall",
            limitation = "Manual comparison model; import the four verified recognition files from the fixed archive. Public download/distribution audit pending."
        )

        /** Exact files extracted from the pinned GitHub archive; import only pending distribution audit. */
        val XAsrStreaming960 = ModelCatalogEntry(
            LocalAsrModel.XAsrStreaming960,
            version = "2026-06-05 (asset 460927089)",
            files = listOf(
                ModelFile("encoder.int8.onnx", 155276576, "017e3cf23097302dbc57ebd72cf4a209cf55c367920669e2d9ce9c0381a96ddd"),
                ModelFile("decoder.onnx", 11309084, "a1cbc9eac2d5e3fb6617a218c67ad6daaa7f4e0fd225f08b2c22ab0413c8c257"),
                ModelFile("joiner.int8.onnx", 2581422, "aedb7fa697b2ab43f20499826fff7c997eea7d67db77be97769aeeeb726e63b3"),
                ModelFile("tokens.txt", 58806, "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c")
            ),
            downloadBase = null,
            sourceName = "k2-fsa/sherpa-onnx/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05",
            sourceUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-x-asr-960ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2",
            license = "Apache-2.0 (author declaration; archive has no LICENSE/NOTICE)",
            licenseUrl = "https://huggingface.co/GilgameshWind/X-ASR-zh-en/blob/689ff18c584d29910da37b6fe904db0c1489c9d1/README.md",
            attribution = "Gilgamesh-J/X-ASR; Fangjun Kuang / Xiaomi sherpa-onnx export; k2-fsa/icefall",
            limitation = "Manual comparison model; import the four verified recognition files from the fixed archive. Public download/distribution audit pending."
        )

        /** User-facing order; recommendation eligibility is separate. */
        val entries = listOf(FunAsrNano, ZipformerBilingual, XAsrOffline, XAsrStreaming960)

        private val HF_RESOLVE = Regex("""https://huggingface\.co/([^/]+/[^/]+)/resolve/([0-9a-f]{40})/?""")

        fun of(model: LocalAsrModel) = entries.first { it.model == model }
    }
}

/** Why an installation stopped; nothing incomplete is ever installed in any of these cases. */
internal sealed class InstallFailure(message: String) : Exception(message) {
    class Cancelled : InstallFailure("cancelled")
    class Paused : InstallFailure("paused")
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

    /** Discard only download staging, never the installed or legacy model. */
    fun discardStaging(model: LocalAsrModel) {
        val tmp = stagingDir(model)
        if (tmp.exists() && !tmp.deleteRecursively()) throw IOException("cannot discard ${tmp.name}")
    }

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
        attempts: Int = 1,
        paused: () -> Boolean = { false },
        downloadPhase: (Boolean) -> Unit = {}
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
                        copyVerified(file, source, tmp, cancelled, paused, downloadPhase) { n ->
                            onProgress(done + n, needed)
                        }
                        break
                    } catch (e: InstallFailure) {
                        if (e is InstallFailure.Cancelled || e is InstallFailure.Paused ||
                            e is InstallFailure.Missing || ++attempt >= attempts) {
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
        paused: () -> Boolean,
        downloadPhase: (Boolean) -> Unit,
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
            downloadPhase(true)
            val stream = source(file, have) ?: throw InstallFailure.Missing(file.path)
            stream.input.use { src ->
                // append only when the source really continues where the staged bytes end
                val append = have > 0 && stream.offset == have
                if (append) {
                    downloadPhase(false)
                    target.inputStream().use { digest.update(it) }
                    downloadPhase(true)
                } else have = 0
                copied = have
                progress(copied)
                FileOutputStream(target, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw InstallFailure.Cancelled()
                        if (paused()) throw InstallFailure.Paused()
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
            // Closing the gate checks a pending Pause before digest/final install can proceed.
            downloadPhase(false)
        } catch (e: IOException) {
            // a cancelled download fails its blocking read on purpose
            if (cancelled()) {
                target.delete()
                throw InstallFailure.Cancelled()
            }
            if (paused()) throw InstallFailure.Paused()
            // the bytes written so far stay staged, so a retry can resume after them
            throw InstallFailure.Io(file.path, e)
        } catch (e: InstallFailure) {
            if (e !is InstallFailure.Missing && e !is InstallFailure.Paused) target.delete()
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
