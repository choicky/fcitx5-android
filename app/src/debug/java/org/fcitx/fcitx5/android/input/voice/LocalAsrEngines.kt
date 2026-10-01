/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** Debug builds: sherpa-onnx v1.13.8 (official AAR, its own JNI and Kotlin API). */
internal object LocalAsrEngines {

    const val AVAILABLE = true

    fun load(model: LocalAsrModel, modelDir: File, threads: Int): LocalAsrRecognizer =
        when (model) {
            LocalAsrModel.FunAsrNano -> funAsrNano(modelDir, threads)
            // model type left empty: sherpa-onnx reads it from the model metadata
            LocalAsrModel.ZipformerBilingual -> zipformer(
                modelDir, threads, "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx", ""
            )
        }

    private fun zipformer(
        dir: File,
        threads: Int,
        encoder: String,
        decoder: String,
        joiner: String,
        modelType: String
    ): LocalAsrRecognizer {
        val recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = dir.resolve(encoder).path,
                        decoder = dir.resolve(decoder).path,
                        joiner = dir.resolve(joiner).path
                    ),
                    tokens = dir.resolve("tokens.txt").path,
                    numThreads = threads,
                    modelType = modelType
                ),
                // push-to-talk: the user ends the utterance, not the endpoint detector
                enableEndpoint = false
            )
        )
        return object : LocalAsrRecognizer {
            override fun newSession(): LocalAsrSession {
                val stream = recognizer.createStream()
                return StreamingLocalAsrSession(object : StreamingDecoder {
                    override fun acceptWaveform(samples: FloatArray) =
                        stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)

                    override fun decodeReady(): String {
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                        return recognizer.getResult(stream).text
                    }

                    override fun inputFinished() = stream.inputFinished()

                    override fun release() = stream.release()
                })
            }

            override fun release() = recognizer.release()
        }
    }

    private fun funAsrNano(dir: File, threads: Int): LocalAsrRecognizer {
        val recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    funasrNano = OfflineFunAsrNanoModelConfig(
                        encoderAdaptor = dir.resolve("encoder_adaptor.int8.onnx").path,
                        llm = dir.resolve("llm.int8.onnx").path,
                        embedding = dir.resolve("embedding.int8.onnx").path,
                        tokenizer = dir.resolve("Qwen3-0.6B").path
                    ),
                    numThreads = threads
                )
            )
        )
        return object : LocalAsrRecognizer {
            override fun newSession(): LocalAsrSession = BufferingLocalAsrSession { samples ->
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text
                } finally {
                    stream.release()
                }
            }

            override fun release() = recognizer.release()
        }
    }
}
