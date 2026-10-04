/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import java.io.File

/**
 * Local-only record of Voice control / data-flow decisions, so a session's routing can be
 * reconstructed after the fact without ever storing what was said or any credential. It observes
 * existing behavior from [VoiceInputComponent]; nothing reads it back, and a failed write is
 * silently ignored — it can never affect a session (F1 audit contract, `/docs`-level design).
 *
 * Privacy posture matches [LastErrorRecord]: kept in the no-backup directory, outside shared
 * preferences, so it is neither backed up nor part of the user-data export.
 *
 * By construction no [VoiceAuditEvent] can carry free text derived from content: every string is
 * a closed-set label, and [encode] filters and caps each field. Raw or partial/final transcripts,
 * committed text, audio/level, error details and self-hosted URLs are never accepted here.
 */
internal class VoiceAudit(
    private val file: () -> File,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** Best-effort append; the audit is never allowed to throw into or influence a session. */
    fun record(event: VoiceAuditEvent) = runCatching {
        val target = FileReplace.recover(file())
        target.parentFile?.mkdirs()
        val existing = if (target.exists()) target.readLines() else emptyList()
        val kept = (existing + encode(event, now())).takeLast(MAX_RECORDS)
        val tmp = File(target.path + ".tmp")
        tmp.writeText(kept.joinToString("\n"))
        if (!FileReplace.replace(tmp, target)) tmp.delete()
    }

    /** The recorded lines in append order (oldest first); used by tests in this batch. */
    fun read(): List<String> = runCatching { FileReplace.recover(file()).readLines() }.getOrDefault(emptyList())

    companion object {
        const val MAX_RECORDS = 500
        private const val MAX_FIELD = 64
        private val unsafe = Regex("[^A-Za-z0-9._:+\\-]")

        fun auditFile(context: Context): File = context.noBackupFilesDir.resolve("voice/session-audit.log")

        internal fun encode(event: VoiceAuditEvent, timestamp: Long): String {
            val sb = StringBuilder(48)
                .append(timestamp).append(' ')
                .append(event.token).append(' ')
                .append(event.code)
            for ((key, value) in event.fields) {
                sb.append(' ').append(key).append('=').append(sanitize(value))
            }
            return sb.toString()
        }

        // drops anything outside the closed-set alphabet and caps length, so no content can slip
        // through even if a field is later wired up wrong
        private fun sanitize(value: String): String = value.replace(unsafe, "").take(MAX_FIELD)
    }
}

/** Safe, closed-set routing labels; the values written to disk come only from these. */
internal enum class VoiceAuditRoute(val label: String) {
    Start("start"),
    External("external"),
    NeedRecordAudio("needRecordAudio"),
    NeedSystemAuth("needSystemAuth"),
    Recommend("recommend"),
    Unavailable("unavailable"),
}

/** The two outcomes of an External Android hand-off; there is no third. */
internal enum class VoiceAuditHandoff(val label: String) {
    Switched("switched"),
    NoIme("noIme"),
}

/** Where the microphone audio is sent for this session — the core data-flow fact. */
internal enum class VoiceAuditDest(val label: String) {
    OnDevice("onDevice"),
    ManagedCloud("managedCloud"),
    SelfHosted("selfHosted"),
}

/**
 * One observed control / data-flow decision. [token] is the audit session id (0 before an in-IME
 * session is bound); it is stable across a D035 fallback, unlike the internal backend generation.
 */
internal sealed interface VoiceAuditEvent {
    val token: Long
    val code: String
    val fields: List<Pair<String, String>>
}

internal data class StartEvent(
    override val token: Long,
    val route: VoiceAuditRoute,
    val reason: String? = null,
    val recommendation: String? = null,
    val model: String? = null,
) : VoiceAuditEvent {
    override val code = "start"
    override val fields: List<Pair<String, String>>
        get() = buildList {
            add("route" to route.label)
            reason?.let { add("reason" to it) }
            recommendation?.let { add("recommendation" to it) }
            model?.let { add("model" to it) }
        }
}

internal data class RoutedEvent(
    override val token: Long,
    val dest: VoiceAuditDest,
    val kind: String,
    val external: Boolean,
    val fallbackArmed: Boolean,
    val language: String,
) : VoiceAuditEvent {
    override val code = "routed"
    override val fields: List<Pair<String, String>>
        get() = buildList {
            add("dest" to dest.label)
            add("kind" to kind)
            add("ext" to external.toString())
            add("fb" to fallbackArmed.toString())
            if (language.isNotBlank()) add("lang" to language)
        }
}

internal data class HandoffEvent(
    override val token: Long,
    val outcome: VoiceAuditHandoff,
    val to: String? = null,
) : VoiceAuditEvent {
    override val code = "handoff"
    override val fields: List<Pair<String, String>>
        get() = buildList {
            add("outcome" to outcome.label)
            to?.let { add("to" to it) }
        }
}

internal data class StateEvent(
    override val token: Long,
    val state: VoiceInputSession.State,
) : VoiceAuditEvent {
    override val code = "state"
    override val fields: List<Pair<String, String>> get() = listOf("state" to state.name)
}

internal data class CommittedEvent(override val token: Long) : VoiceAuditEvent {
    override val code = "committed"
    override val fields: List<Pair<String, String>> get() = emptyList()
}

internal data class StopEvent(override val token: Long) : VoiceAuditEvent {
    override val code = "stop"
    override val fields: List<Pair<String, String>> get() = emptyList()
}

internal data class CancelEvent(override val token: Long) : VoiceAuditEvent {
    override val code = "cancel"
    override val fields: List<Pair<String, String>> get() = emptyList()
}

internal data class ErrorEvent(
    override val token: Long,
    val errorClass: String,
    val systemCode: Int? = null,
) : VoiceAuditEvent {
    override val code = "error"
    override val fields: List<Pair<String, String>>
        get() = buildList {
            add("class" to errorClass)
            systemCode?.let { add("code" to it.toString()) }
        }
}

internal data class FallbackEvent(
    override val token: Long,
    val toModel: String,
) : VoiceAuditEvent {
    override val code = "fallback"
    override val fields: List<Pair<String, String>> get() = listOf("to" to toModel)
}

// ---- pure domain -> safe audit mapping (the testable surface) ----

private val Recommendation.auditLabel: String
    get() = when (this) {
        is Recommendation.SelectLocal -> "selectLocal"
        Recommendation.SelectSystem -> "selectSystem"
        Recommendation.AskSystemAuthorization -> "askSystemAuth"
        Recommendation.Nothing -> "nothing"
    }

internal fun VoiceStartStep.toAuditStart(token: Long): StartEvent = when (this) {
    is VoiceStartStep.Start -> StartEvent(token, VoiceAuditRoute.Start)
    VoiceStartStep.StartExternal -> StartEvent(token, VoiceAuditRoute.External)
    VoiceStartStep.RequestRecordAudio -> StartEvent(token, VoiceAuditRoute.NeedRecordAudio)
    VoiceStartStep.RequestSystemAuthorization -> StartEvent(token, VoiceAuditRoute.NeedSystemAuth)
    is VoiceStartStep.ApplyRecommendation -> StartEvent(
        token,
        VoiceAuditRoute.Recommend,
        recommendation = recommendation.auditLabel,
        model = (recommendation as? Recommendation.SelectLocal)?.model?.name,
    )
    is VoiceStartStep.Unavailable -> StartEvent(
        token,
        VoiceAuditRoute.Unavailable,
        reason = when (val r = resolution) {
            is AsrResolution.CurrentUnavailable -> r.reason.name
            AsrResolution.NoService -> "NoService"
            is AsrResolution.NeedsRecommendation -> "NoRecommendation"
            else -> "Unavailable"
        },
    )
}

/** (destination, safe kind label). Never the instance url, id or token. */
internal fun VoiceBackendKind.auditDescriptor(): Pair<VoiceAuditDest, String> = when (this) {
    VoiceBackendKind.System -> VoiceAuditDest.OnDevice to "system"
    is VoiceBackendKind.LocalAsr -> VoiceAuditDest.OnDevice to "local:${model.name}"
    VoiceBackendKind.CaptureProbe -> VoiceAuditDest.OnDevice to "captureProbe"
    VoiceBackendKind.Doubao -> VoiceAuditDest.ManagedCloud to "doubao"
    VoiceBackendKind.DoubaoAsr -> VoiceAuditDest.ManagedCloud to "doubaoDebug"
    VoiceBackendKind.Qwen -> VoiceAuditDest.ManagedCloud to "qwen"
    VoiceBackendKind.Tencent -> VoiceAuditDest.ManagedCloud to "tencent"
    is VoiceBackendKind.SelfHosted -> VoiceAuditDest.SelfHosted to "selfhosted:${instance.protocol.key}"
}

internal fun VoiceError.auditClass(): Pair<String, Int?> = when (this) {
    VoiceError.Silent -> "silent" to null
    VoiceError.PermissionDenied -> "permission" to null
    is VoiceError.System -> "system" to code
    is VoiceError.Capture -> "capture" to null // detail is a server/exception echo; not stored
    is VoiceError.Service -> "service" to null
}
