/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Self-hosted server protocols (D033). Only protocols with a working client are listed; each
 * matches an upstream server, see network-asr-checkpoint.md.
 */
internal enum class SelfHostedProtocol(val key: String, val secureScheme: String = "wss") {
    /** sherpa-onnx streaming WebSocket server (float32 samples, `Done`, JSON results). */
    SherpaOnnx("sherpa-onnx"),

    /** FunASR real-time server in 2pass mode (online Paraformer + offline correction). */
    FunAsr2Pass("funasr-2pass"),

    /** FunASR's Fun-ASR-Nano streaming server (`funasr-realtime-server`; START/STOP). */
    FunAsrNano("funasr-nano"),

    /**
     * An OpenAI-compatible `/v1/audio/transcriptions` endpoint: whole-utterance upload after
     * stop, no partial results. Optional adapter; not a replacement for the streaming servers.
     */
    OpenAiCompatible("openai-compatible", secureScheme = "https");

    /** The unencrypted counterpart, only accepted where cleartext is explicitly allowed. */
    val plainScheme get() = if (secureScheme == "https") "http" else "ws"

    companion object {
        fun parse(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** A user-defined self-hosted server; its optional token lives in [CredentialStore]. */
internal data class SelfHostedInstance(
    val id: String,
    val name: String,
    val protocol: SelfHostedProtocol,
    val url: String,
    /** The `model` field for an OpenAI-compatible endpoint; unused by the other protocols. */
    val model: String = ""
) {
    val service get() = AsrServiceId.SelfHosted(id)

    /** The credential store entry holding this instance's token. */
    val credentialProvider get() = "selfhosted-$id"

    companion object {
        const val TOKEN = "token"

        private val idPattern = Regex("[a-z0-9]{1,32}")

        fun isValidId(id: String) = idPattern.matches(id)

        fun encode(instances: List<SelfHostedInstance>): String = buildJsonArray {
            instances.forEach {
                add(buildJsonObject {
                    put("id", it.id)
                    put("name", it.name)
                    put("protocol", it.protocol.key)
                    put("url", it.url)
                    if (it.model.isNotEmpty()) put("model", it.model)
                })
            }
        }.toString()

        /** Unknown protocols or broken entries are dropped instead of failing everything. */
        fun decode(raw: String): List<SelfHostedInstance> {
            if (raw.isBlank()) return emptyList()
            val array = runCatching { Json.parseToJsonElement(raw) as JsonArray }.getOrNull()
                ?: return emptyList()
            return array.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                fun field(name: String) = o[name]?.jsonPrimitive?.contentOrNull
                val id = field("id")?.takeIf(::isValidId) ?: return@mapNotNull null
                val protocol = field("protocol")?.let(SelfHostedProtocol::parse) ?: return@mapNotNull null
                SelfHostedInstance(
                    id, field("name").orEmpty(), protocol, field("url").orEmpty(), field("model").orEmpty()
                )
            }
        }
    }
}

internal enum class EndpointProblem { Invalid, Cleartext }

/**
 * An endpoint must use the protocol's encrypted scheme (`wss://`, or `https://` for the
 * OpenAI-compatible adapter); the plain scheme is only accepted where cleartext is explicitly
 * allowed (debug builds), never silently. Certificates are checked against the system trust
 * store; there is no trust-all option.
 */
internal fun endpointProblem(
    url: String,
    allowCleartext: Boolean,
    protocol: SelfHostedProtocol = SelfHostedProtocol.SherpaOnnx
): EndpointProblem? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return EndpointProblem.Invalid
    if (uri.host.isNullOrEmpty()) return EndpointProblem.Invalid
    return when (uri.scheme?.lowercase()) {
        protocol.secureScheme -> null
        protocol.plainScheme -> if (allowCleartext) null else EndpointProblem.Cleartext
        else -> EndpointProblem.Invalid
    }
}

/** What resolution needs to know about external services, without reading any secret. */
internal interface ExternalServices {
    /** Whether a Managed Cloud service has credentials stored. */
    fun configured(service: AsrServiceId): Boolean

    fun instance(id: String): SelfHostedInstance?

    val allowCleartext: Boolean

    object None : ExternalServices {
        override fun configured(service: AsrServiceId) = false
        override fun instance(id: String): SelfHostedInstance? = null
        override val allowCleartext = false
    }
}
