/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

/**
 * A concrete speech recognition service the user can enable and select (D034). Only services
 * that have a working configuration and runtime path are listed; Managed Cloud and Self-hosted
 * services are added together with their backends.
 */
internal sealed interface AsrServiceId {
    val key: String

    /** Managed Cloud and Self-hosted services send audio to an outside recipient. */
    val external: Boolean

    data object System : AsrServiceId {
        override val key = "system"
        override val external = false
    }

    /**
     * One installed on-device model. Each model is enabled and selected on its own, like a
     * cloud provider; a session runs only the selected model.
     */
    data class Local(val model: LocalAsrModel) : AsrServiceId {
        override val key get() = LOCAL_PREFIX + model.name
        override val external get() = false
    }

    /** Managed Cloud: Doubao / Seed-ASR 2.0 with the user's own Volcengine credentials. */
    data object Doubao : AsrServiceId {
        override val key = "doubao"
        override val external = true
    }

    /** Managed Cloud: Alibaba Model Studio real-time ASR (Qwen-Audio / Fun-ASR), user's key. */
    data object Qwen : AsrServiceId {
        override val key = "qwen"
        override val external = true
    }

    /** Managed Cloud: Tencent Cloud real-time ASR with the user's own keys. */
    data object Tencent : AsrServiceId {
        override val key = "tencent"
        override val external = true
    }

    /** A user-defined self-hosted server instance. */
    data class SelfHosted(val instanceId: String) : AsrServiceId {
        override val key get() = PREFIX + instanceId
        override val external get() = true
    }

    companion object {
        private const val PREFIX = "selfhosted:"
        private const val LOCAL_PREFIX = "local:"

        /**
         * The single Local service before models were enabled one by one; only read by
         * [migrateLocalModels].
         */
        const val LEGACY_LOCAL_KEY = "local"

        /** The fixed services; self-hosted instances are listed by their store. */
        val entries: List<AsrServiceId> =
            listOf(System) + LocalAsrModel.userVisibleEntries.map(::Local) + listOf(Doubao, Qwen, Tencent)

        fun parse(key: String): AsrServiceId? =
            if (key.startsWith(PREFIX)) {
                key.removePrefix(PREFIX).takeIf(SelfHostedInstance::isValidId)?.let(::SelfHosted)
            } else {
                entries.firstOrNull { it.key == key }
                    ?: key.takeIf { it == Local(LocalAsrModel.ZipformerZh).key }
                        ?.let { Local(LocalAsrModel.ZipformerZh) }
            }
    }
}

/** The backends a session can run on. */
internal sealed interface VoiceBackendKind {
    data object System : VoiceBackendKind
    data object CaptureProbe : VoiceBackendKind
    /** Debug PoC with build-time credentials; not the product path. */
    data object DoubaoAsr : VoiceBackendKind

    /** Product Doubao with the user's stored credentials (Direct BYOK). */
    data object Doubao : VoiceBackendKind

    /** Model Studio real-time ASR with the user's stored key. */
    data object Qwen : VoiceBackendKind

    /** Tencent Cloud real-time ASR with the user's stored keys. */
    data object Tencent : VoiceBackendKind

    /** A self-hosted server; its token is read from the credential store when starting. */
    data class SelfHosted(val instance: SelfHostedInstance) : VoiceBackendKind
    data class LocalAsr(val model: LocalAsrModel) : VoiceBackendKind
}

/**
 * The user's answer to the one-time System ASR disclosure. System ASR is provided by
 * Android/device services that may process audio remotely, so it is not privacy-equivalent to
 * Local ASR and is only used once allowed. This is separate from the RECORD_AUDIO permission.
 */
internal enum class SystemAsrAuthorization {
    NotAsked, Allowed, Declined;

    companion object {
        fun of(allowed: Boolean, answered: Boolean) = when {
            allowed -> Allowed
            answered -> Declined
            else -> NotAsked
        }
    }
}

/** Persisted selection state; configuration, enablement and the current choice are separate. */
internal data class VoiceSelection(
    val current: AsrServiceId?,
    val enabled: Set<AsrServiceId>,
    /** The one-time first-setup recommendation has run (or a choice was migrated). */
    val recommendationDone: Boolean
) {
    fun isEnabled(service: AsrServiceId) = service in enabled

    fun withEnabled(service: AsrServiceId, on: Boolean) =
        copy(enabled = if (on) enabled + service else enabled - service)
}

/**
 * What on-device recognition can offer right now: the runtime and which models are completely
 * installed (a partial or unverified download is not installed). No model is loaded to find out.
 */
internal data class LocalStatus(
    val runtimeAvailable: Boolean,
    val installed: Set<LocalAsrModel>
) {
    /** Selectable as a current service: enabled, installed, and this build can run it. */
    fun usable(model: LocalAsrModel, selection: VoiceSelection) =
        runtimeAvailable && model in LocalAsrModel.userVisibleEntries &&
                model in installed && selection.isEnabled(AsrServiceId.Local(model))
}

internal enum class UnavailableReason {
    Disabled, RetiredLocalModel, NoSystemRecognizer, NoLocalRuntime, LocalModelFilesMissing,
    MissingCredentials, InstanceMissing, InvalidEndpoint, CleartextEndpoint
}

/** What the one-time recommendation would do (D034). It never picks a network service. */
internal sealed interface Recommendation {
    /** Select System ASR, which the user already allowed. */
    data object SelectSystem : Recommendation

    /** System ASR would be recommended, but the disclosure has to be answered first. */
    data object AskSystemAuthorization : Recommendation

    /** Nothing can be recommended; the user has to set up a service. */
    data object Nothing : Recommendation
}

/**
 * Research Local models (D037) are never recommended, so only System can be; a production
 * Local model will come first once one exists. [systemAvailable] is only queried when needed.
 */
internal fun recommend(
    systemAuthorization: SystemAsrAuthorization,
    systemAvailable: () -> Boolean
): Recommendation = when {
    systemAuthorization == SystemAsrAuthorization.Declined -> Recommendation.Nothing
    !systemAvailable() -> Recommendation.Nothing
    systemAuthorization == SystemAsrAuthorization.Allowed -> Recommendation.SelectSystem
    else -> Recommendation.AskSystemAuthorization
}

/** What a voice session would use now. Resolution only: fallback is decided by the flow. */
internal sealed interface AsrResolution {
    data class Ready(val service: AsrServiceId?, val backend: VoiceBackendKind) : AsrResolution

    /** System ASR is the current service, but the user has not allowed it yet. */
    data object NeedsSystemAuthorization : AsrResolution

    /** No service is selected yet and the one-time recommendation still has to run. */
    data class NeedsRecommendation(val recommendation: Recommendation) : AsrResolution

    /** The selected service cannot be used; the saved selection is kept. */
    data class CurrentUnavailable(val service: AsrServiceId, val reason: UnavailableReason) :
        AsrResolution

    /** Nothing is selected and the recommendation has already run. */
    data object NoService : AsrResolution
}

/**
 * The trigger is offered when a session can start or when tapping it leads to the System ASR
 * disclosure; a selected service that cannot be used hides it (D030).
 */
internal val AsrResolution.offersTrigger: Boolean
    get() = when (this) {
        is AsrResolution.Ready, AsrResolution.NeedsSystemAuthorization -> true
        is AsrResolution.NeedsRecommendation -> recommendation != Recommendation.Nothing
        is AsrResolution.CurrentUnavailable, AsrResolution.NoService -> false
    }

/**
 * Formal resolution of the current service (D034). The saved selection is never changed here.
 * [systemAvailable] is only queried when System ASR is actually considered (D030).
 */
internal fun resolveCurrentService(
    selection: VoiceSelection,
    local: LocalStatus,
    systemAuthorization: SystemAsrAuthorization,
    systemAvailable: () -> Boolean,
    external: ExternalServices = ExternalServices.None
): AsrResolution {
    val current = selection.current
        ?: return if (selection.recommendationDone) AsrResolution.NoService
        else AsrResolution.NeedsRecommendation(recommend(systemAuthorization, systemAvailable))
    if (!selection.isEnabled(current)) {
        return AsrResolution.CurrentUnavailable(current, UnavailableReason.Disabled)
    }
    return when (current) {
        AsrServiceId.System -> when {
            !systemAvailable() ->
                AsrResolution.CurrentUnavailable(current, UnavailableReason.NoSystemRecognizer)
            systemAuthorization == SystemAsrAuthorization.Allowed ->
                AsrResolution.Ready(current, VoiceBackendKind.System)
            // an explicit choice asks again after a decline
            else -> AsrResolution.NeedsSystemAuthorization
        }
        is AsrServiceId.Local -> when {
            current.model !in LocalAsrModel.userVisibleEntries ->
                AsrResolution.CurrentUnavailable(current, UnavailableReason.RetiredLocalModel)
            !local.runtimeAvailable ->
                AsrResolution.CurrentUnavailable(current, UnavailableReason.NoLocalRuntime)
            current.model !in local.installed ->
                AsrResolution.CurrentUnavailable(current, UnavailableReason.LocalModelFilesMissing)
            else -> AsrResolution.Ready(current, VoiceBackendKind.LocalAsr(current.model))
        }
        AsrServiceId.Doubao, AsrServiceId.Qwen, AsrServiceId.Tencent ->
            if (!external.configured(current)) {
                AsrResolution.CurrentUnavailable(current, UnavailableReason.MissingCredentials)
            } else {
                AsrResolution.Ready(
                    current,
                    when (current) {
                        AsrServiceId.Qwen -> VoiceBackendKind.Qwen
                        AsrServiceId.Tencent -> VoiceBackendKind.Tencent
                        else -> VoiceBackendKind.Doubao
                    }
                )
            }
        is AsrServiceId.SelfHosted -> {
            val instance = external.instance(current.instanceId)
                ?: return AsrResolution.CurrentUnavailable(current, UnavailableReason.InstanceMissing)
            when (endpointProblem(instance.url, external.allowCleartext, instance.protocol)) {
                null -> AsrResolution.Ready(current, VoiceBackendKind.SelfHosted(instance))
                EndpointProblem.Invalid ->
                    AsrResolution.CurrentUnavailable(current, UnavailableReason.InvalidEndpoint)
                EndpointProblem.Cleartext ->
                    AsrResolution.CurrentUnavailable(current, UnavailableReason.CleartextEndpoint)
            }
        }
    }
}

/**
 * D035: only a selected external (Managed Cloud / Self-hosted) service falls back, and only to
 * an enabled, installed production Local model. Research models (D037, A/B/C) and System ASR
 * never are fallback targets, so there is no target until a production Local model exists.
 */
internal fun fallbackTarget(
    selected: AsrServiceId?,
    selection: VoiceSelection,
    local: LocalStatus
): VoiceBackendKind? {
    if (selected == null || !selected.external) return null
    return LocalAsrModel.userVisibleEntries
        .firstOrNull { it.production && local.usable(it, selection) }
        ?.let(VoiceBackendKind::LocalAsr)
}

/**
 * Debug-only PoC backends that bypass the formal selection, in this order: Doubao Direct, then
 * the capture probe. Release builds never override.
 */
internal fun debugBackendOverride(
    debug: Boolean,
    doubaoAsr: Boolean,
    captureProbe: Boolean
): VoiceBackendKind? = when {
    !debug -> null
    doubaoAsr -> VoiceBackendKind.DoubaoAsr
    captureProbe -> VoiceBackendKind.CaptureProbe
    else -> null
}

/** The one place that decides a session's backend: a debug override, else the current service. */
internal fun resolveVoiceBackend(
    debugOverride: VoiceBackendKind?,
    selection: VoiceSelection,
    local: LocalStatus,
    systemAuthorization: SystemAsrAuthorization,
    systemAvailable: () -> Boolean,
    external: ExternalServices = ExternalServices.None
): AsrResolution = debugOverride?.let { AsrResolution.Ready(null, it) }
    ?: resolveCurrentService(selection, local, systemAuthorization, systemAvailable, external)

/** The selection after the System ASR disclosure is answered. */
internal fun VoiceSelection.afterSystemDisclosure(allowed: Boolean): VoiceSelection = when {
    // the recommendation asked: an allow selects System, a decline ends the recommendation
    current == null && !recommendationDone ->
        if (allowed) {
            copy(current = AsrServiceId.System, recommendationDone = true)
                .withEnabled(AsrServiceId.System, true)
        } else {
            copy(recommendationDone = true)
        }
    else -> this
}

/** Apply a recommendation that needs no further answer. */
internal fun VoiceSelection.applyRecommendation(recommendation: Recommendation): VoiceSelection =
    when (recommendation) {
        Recommendation.SelectSystem ->
            copy(current = AsrServiceId.System, recommendationDone = true)
                .withEnabled(AsrServiceId.System, true)
        Recommendation.Nothing -> copy(recommendationDone = true)
        // the disclosure answer decides; see afterSystemDisclosure
        Recommendation.AskSystemAuthorization -> this
    }

/**
 * Migration from the earlier persisted Auto/Local/System setting (`fb3b0c26`). Never selects a
 * network service. An old Auto keeps System only when it was already allowed, remembers a
 * decline, and otherwise leaves the one-time recommendation to run on first use. An old Local
 * becomes the research model chosen in the Developer screen, or no selection without one.
 */
internal fun migrateLegacyProvider(
    legacy: String?,
    systemAuthorization: SystemAsrAuthorization,
    localModel: LocalAsrModel? = null
): VoiceSelection {
    val none = VoiceSelection(null, emptySet(), recommendationDone = false)
    return when (legacy) {
        "System" -> VoiceSelection(AsrServiceId.System, setOf(AsrServiceId.System), recommendationDone = true)
        "Local" -> localModel?.let(AsrServiceId::Local)
            ?.let { VoiceSelection(it, setOf(it), recommendationDone = true) }
            ?: none.copy(recommendationDone = true)
        // "Auto", unset, or anything unknown
        else -> when (systemAuthorization) {
            SystemAsrAuthorization.Allowed -> none.applyRecommendation(Recommendation.SelectSystem)
            SystemAsrAuthorization.Declined -> none.applyRecommendation(Recommendation.Nothing)
            SystemAsrAuthorization.NotAsked -> none
        }
    }
}

/**
 * Migration from the single Local service (one "enable Local" switch plus one configured model,
 * current key `local`) to one service per model. The effective configuration is kept: the
 * configured model is enabled if Local was enabled, and a current Local becomes that model.
 * A current Local without a configured model could not run before; it becomes no selection
 * (the recommendation does not run again). Nothing new is enabled or selected.
 */
internal fun migrateLocalModels(
    currentKey: String,
    localEnabled: Boolean,
    localModel: LocalAsrModel?
): Pair<AsrServiceId?, Set<AsrServiceId>> {
    val service = localModel?.let(AsrServiceId::Local)
    val enabled = if (localEnabled && service != null) setOf<AsrServiceId>(service) else emptySet()
    val current = if (currentKey == AsrServiceId.LEGACY_LOCAL_KEY) service else AsrServiceId.parse(currentKey)
    return current to enabled
}

/** What tapping a voice trigger does next. */
internal sealed interface VoiceStartStep {
    data class Start(val backend: VoiceBackendKind) : VoiceStartStep
    data object RequestSystemAuthorization : VoiceStartStep
    data object RequestRecordAudio : VoiceStartStep

    /** Persist this selection, then resolve again. */
    data class ApplyRecommendation(val recommendation: Recommendation) : VoiceStartStep
    data class Unavailable(val resolution: AsrResolution) : VoiceStartStep
}

/**
 * The service is resolved before RECORD_AUDIO is requested: the System ASR disclosure comes
 * before any microphone prompt, and nothing asks for the microphone when no service is usable.
 */
internal fun voiceStartStep(resolution: AsrResolution, recordAudioGranted: Boolean) =
    when (resolution) {
        is AsrResolution.Ready ->
            if (recordAudioGranted) VoiceStartStep.Start(resolution.backend)
            else VoiceStartStep.RequestRecordAudio
        AsrResolution.NeedsSystemAuthorization -> VoiceStartStep.RequestSystemAuthorization
        is AsrResolution.NeedsRecommendation -> when (resolution.recommendation) {
            Recommendation.AskSystemAuthorization -> VoiceStartStep.RequestSystemAuthorization
            else -> VoiceStartStep.ApplyRecommendation(resolution.recommendation)
        }
        else -> VoiceStartStep.Unavailable(resolution)
    }

/** The debug preference value is a [LocalAsrModel] name, or anything else for none. */
internal fun localAsrModel(value: String): LocalAsrModel? =
    LocalAsrModel.entries.firstOrNull { it.name == value }

internal fun localAsrThreads(value: String): Int =
    value.toIntOrNull()?.coerceIn(1, 4) ?: DEFAULT_LOCAL_ASR_THREADS

internal const val DEFAULT_LOCAL_ASR_THREADS = 2
