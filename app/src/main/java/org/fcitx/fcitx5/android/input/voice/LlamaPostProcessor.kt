/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The frozen v1 post-processing prompt (task §5), never revised during the PoC: byte-identical
 * to `prompt-v1.txt` — 982 bytes, the continuation inside rule 2 indented by three spaces, no
 * trailing newline — which is what the host probe sent. Identity asserted by
 * [LlamaPostProcessorPromptTest] so the bytes the device sends stay the bytes the host
 * evidence was produced with.
 */
internal const val POSTPROC_PROMPT_V1 = """你是中文语音转写文本的校对员。只校对，不创作。

规则（全部必须遵守）：
1. 只做三件事：补全或修正标点；调整中英文之间的空格；纠正确定无疑的同音字或上下文用字错误。
2. 必须原样保留：数字、日期、时间、金额、编号、人名、地名、公司名、产品名、项目名、英文术语、
   缩写、文件名、代码标识符、URL、邮箱。不得改写、翻译、缩写、展开或改动它们的大小写。
3. 不得改变原意，不得调整语序，不得替换同义词，不得补全省略的内容，不得删除口语词。
4. 不得总结，不得解释，不得回答问题，不得添加原文没有的信息，不得添加注释或说明。
5. 如果原文已经正确，就一字不改地原样返回。
6. 如果某处不确定是否错误，就不要改。
7. 只输出校对后的文本本身：不要引号、不要前缀、不要代码块、不要第二行说明。"""

/**
 * Gate-B PoC runtime for [TextPostProcessor]: llama.cpp v0.6.0, CPU-only, built statically
 * into `libpocpostprocessor.so` (arm64-v8a only). Debug builds only; everything that can
 * fail — missing library, missing sideloaded model, non-Chinese transcript, load or
 * generation error — answers null, and the flow then delivers the raw transcript.
 *
 * The model is never bundled (task §4): it is sideloaded as the single `*.gguf` under
 * `<external files>/postproc/`, and its presence is the on/off switch — no settings UI.
 */
internal class LlamaPostProcessor private constructor(
    private val context: () -> Context
) : TextPostProcessor {

    private external fun nativeLoad(modelPath: String, threads: Int, nCtx: Int): Boolean
    private external fun nativeProcess(
        seq: Long,
        raw: String,
        systemPrompt: String,
        maxTokens: Int,
        deadlineMs: Int
    ): String?

    private external fun nativeCancel(seq: Long)

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llama-postproc").apply { isDaemon = true }
    }

    /** Monotonic sequence; each [process] call supersedes any generation still running. */
    private val seq = AtomicLong(0)

    @Volatile
    private var pendingSeq = -1L

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loadedPath: String? = null

    override fun process(raw: String, languageTag: String, callback: (processed: String?) -> Unit) {
        // the frozen prompt is a Chinese proofreading prompt; other languages stay raw
        if (!languageTag.startsWith("zh", ignoreCase = true)) {
            Timber.d("postproc: pass-through for languageTag %s", languageTag)
            main.post { callback(null) }
            return
        }
        val n = seq.incrementAndGet()
        // single-flight supersede: abort the generation still running for the previous final
        nativeCancel(pendingSeq)
        pendingSeq = n
        worker.execute {
            val result = try {
                ensureLoaded()?.let { modelPath ->
                    nativeProcess(n, raw, POSTPROC_PROMPT_V1, MAX_TOKENS, DEADLINE_MS)
                }
            } catch (t: Throwable) {
                Timber.w(t, "postproc: generation failed")
                null
            }
            // exactly-once on the main thread; a stale answer is dropped by the flow's
            // ownership marker, never by this class
            main.post { callback(result) }
        }
    }

    /** First `*.gguf` in the sideload dir; absence means the PoC stage is switched off. */
    private fun modelFile(): File? =
        context().getExternalFilesDir(null)?.resolve("postproc")?.listFiles { f -> f.extension == "gguf" }
            ?.firstOrNull()

    @Synchronized
    private fun ensureLoaded(): String? {
        val file = modelFile() ?: run {
            Timber.d("postproc: no sideloaded model under <external files>/postproc/")
            return null
        }
        val path = file.absolutePath
        loadedPath?.let {
            if (it != path) Timber.w("postproc: %s loaded; swap requires an app restart", it)
            return if (it == path) path else it
        }
        if (loadAttempted) return null
        loadAttempted = true
        Timber.i("postproc: loading %s", file.name)
        return if (nativeLoad(path, THREADS, CTX)) { loadedPath = path; path } else null
    }

    companion object {
        // same settings as the host probe evidence (raw-results/): comparability over tuning
        private const val THREADS = 4
        private const val CTX = 2048
        private const val MAX_TOKENS = 192

        /** Wall-clock bound on one generation; the flow's own window is TextPostPolicy.TIMEOUT_MS. */
        private const val DEADLINE_MS = 8_000

        /**
         * Debug PoC factory: null in release builds, null when `libpoc-postprocessor.so` is
         * absent (any non-arm64-v8a build), so the flow keeps its previous behaviour exactly.
         */
        fun poc(context: () -> Context): TextPostProcessor? {
            if (!BuildConfig.DEBUG) return null
            return try {
                System.loadLibrary("poc-postprocessor")
                LlamaPostProcessor(context)
            } catch (e: UnsatisfiedLinkError) {
                Timber.d(e, "postproc: native runtime absent")
                null
            } catch (e: IllegalStateException) {
                Timber.d(e, "postproc: native runtime not loadable")
                null
            }
        }
    }
}
