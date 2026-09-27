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

    data object System : AsrServiceId {
        override val key = "system"
    }

    data object Local : AsrServiceId {
        override val key = "local"
    }

    companion object {
        val entries: List<AsrServiceId> = listOf(System, Local)

        fun parse(key: String): AsrServiceId? = entries.firstOrNull { it.key == key }
    }
}

/** The backends a session can run on. */
internal sealed interface VoiceBackendKind {
    data object System : VoiceBackendKind
    data object CaptureProbe : VoiceBackendKind
    data object DoubaoAsr : VoiceBackendKind
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
    val systemEnabled: Boolean,
    val localEnabled: Boolean,
    /** The one-time first-setup recommendation has run (or a choice was migrated). */
    val recommendationDone: Boolean
) {
    fun isEnabled(service: AsrServiceId) = when (service) {
        AsrServiceId.System -> systemEnabled
        AsrServiceId.Local -> localEnabled
    }
}

/** What the Local service can offer right now; no model is loaded to find out. */
internal data class LocalStatus(
    val runtimeAvailable: Boolean,
    val model: LocalAsrModel?,
    val filesPresent: Boolean
)

internal enum class UnavailableReason {
    Disabled, NoSystemRecognizer, NoLocalRuntime, NoLocalModel, LocalModelFilesMissing
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
    systemAvailable: () -> Boolean
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
        AsrServiceId.Local -> {
            val model = local.model
            when {
                !local.runtimeAvailable ->
                    AsrResolution.CurrentUnavailable(current, UnavailableReason.NoLocalRuntime)
                model == null ->
                    AsrResolution.CurrentUnavailable(current, UnavailableReason.NoLocalModel)
                !local.filesPresent ->
                    AsrResolution.CurrentUnavailable(current, UnavailableReason.LocalModelFilesMissing)
                else -> AsrResolution.Ready(current, VoiceBackendKind.LocalAsr(model))
            }
        }
    }
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
    systemAvailable: () -> Boolean
): AsrResolution = debugOverride?.let { AsrResolution.Ready(null, it) }
    ?: resolveCurrentService(selection, local, systemAuthorization, systemAvailable)

/** The selection after the System ASR disclosure is answered. */
internal fun VoiceSelection.afterSystemDisclosure(allowed: Boolean): VoiceSelection = when {
    // the recommendation asked: an allow selects System, a decline ends the recommendation
    current == null && !recommendationDone ->
        if (allowed) copy(current = AsrServiceId.System, systemEnabled = true, recommendationDone = true)
        else copy(recommendationDone = true)
    else -> this
}

/** Apply a recommendation that needs no further answer. */
internal fun VoiceSelection.applyRecommendation(recommendation: Recommendation): VoiceSelection =
    when (recommendation) {
        Recommendation.SelectSystem ->
            copy(current = AsrServiceId.System, systemEnabled = true, recommendationDone = true)
        Recommendation.Nothing -> copy(recommendationDone = true)
        // the disclosure answer decides; see afterSystemDisclosure
        Recommendation.AskSystemAuthorization -> this
    }

/**
 * Migration from the earlier persisted Auto/Local/System setting (`fb3b0c26`). Never selects a
 * network service. An old Auto keeps System only when it was already allowed, remembers a
 * decline, and otherwise leaves the one-time recommendation to run on first use.
 */
internal fun migrateLegacyProvider(
    legacy: String?,
    systemAuthorization: SystemAsrAuthorization
): VoiceSelection {
    val none = VoiceSelection(null, systemEnabled = false, localEnabled = false, recommendationDone = false)
    return when (legacy) {
        "System" -> VoiceSelection(AsrServiceId.System, systemEnabled = true, localEnabled = false, recommendationDone = true)
        "Local" -> VoiceSelection(AsrServiceId.Local, systemEnabled = false, localEnabled = true, recommendationDone = true)
        // "Auto", unset, or anything unknown
        else -> when (systemAuthorization) {
            SystemAsrAuthorization.Allowed -> none.applyRecommendation(Recommendation.SelectSystem)
            SystemAsrAuthorization.Declined -> none.applyRecommendation(Recommendation.Nothing)
            SystemAsrAuthorization.NotAsked -> none
        }
    }
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
