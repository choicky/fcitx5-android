/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * The speech recognition service (语音识别服务) the user configured. Only providers with a
 * formal configuration and runtime get an entry; Managed Cloud and Self-hosted come later.
 */
enum class AsrProvider(override val stringRes: Int) : ManagedPreferenceEnum {
    Auto(R.string.asr_provider_auto),
    Local(R.string.asr_provider_local),
    System(R.string.asr_provider_system);
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

/** What a voice session would use right now. Resolution only: there is no runtime fallback. */
internal sealed interface AsrResolution {
    data class Ready(val backend: VoiceBackendKind) : AsrResolution

    /** System ASR would be used, but the user has not allowed it yet. */
    data object NeedsSystemAuthorization : AsrResolution

    /** Local was chosen explicitly, but no usable Local model is configured. */
    data object LocalUnavailable : AsrResolution

    /** System was chosen explicitly, but Android offers no SpeechRecognizer. */
    data object SystemUnavailable : AsrResolution

    /** Auto found nothing it may use; the user has to configure a service. */
    data object NoProvider : AsrResolution
}

/** The trigger is offered when a session can start, or when it would ask for authorization. */
internal val AsrResolution.offersTrigger: Boolean
    get() = this is AsrResolution.Ready || this == AsrResolution.NeedsSystemAuthorization

/**
 * Formal provider resolution (D034). [usableLocal] is a configured Local model whose files are
 * present. [systemAvailable] is only queried when System ASR is actually considered, so a usable
 * Local ASR never depends on SpeechRecognizer (D030). Auto never picks a network provider.
 */
internal fun resolveAsrProvider(
    configured: AsrProvider,
    usableLocal: LocalAsrModel?,
    systemAuthorization: SystemAsrAuthorization,
    systemAvailable: () -> Boolean
): AsrResolution = when (configured) {
    AsrProvider.Auto -> when {
        usableLocal != null -> AsrResolution.Ready(VoiceBackendKind.LocalAsr(usableLocal))
        systemAuthorization == SystemAsrAuthorization.Declined -> AsrResolution.NoProvider
        !systemAvailable() -> AsrResolution.NoProvider
        systemAuthorization == SystemAsrAuthorization.Allowed ->
            AsrResolution.Ready(VoiceBackendKind.System)
        else -> AsrResolution.NeedsSystemAuthorization
    }
    AsrProvider.Local ->
        usableLocal?.let { AsrResolution.Ready(VoiceBackendKind.LocalAsr(it)) }
            ?: AsrResolution.LocalUnavailable
    // an explicit choice asks again after a decline; nothing is asked for a missing service
    AsrProvider.System -> when {
        !systemAvailable() -> AsrResolution.SystemUnavailable
        systemAuthorization == SystemAsrAuthorization.Allowed ->
            AsrResolution.Ready(VoiceBackendKind.System)
        else -> AsrResolution.NeedsSystemAuthorization
    }
}

/**
 * Debug-only PoC backends that bypass the formal provider, in this order: Doubao Direct, then
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

/** The one place that decides a session's backend: a debug override, else the formal provider. */
internal fun resolveVoiceBackend(
    debugOverride: VoiceBackendKind?,
    configured: AsrProvider,
    usableLocal: LocalAsrModel?,
    systemAuthorization: SystemAsrAuthorization,
    systemAvailable: () -> Boolean
): AsrResolution = debugOverride?.let { AsrResolution.Ready(it) }
    ?: resolveAsrProvider(configured, usableLocal, systemAuthorization, systemAvailable)

/**
 * The Local model the resolver may consider. No formal Local model is selected yet (D036), so
 * this is the debug research selection, and only where a Local runtime ships (debug builds).
 */
internal fun configuredLocalModel(runtimeAvailable: Boolean, selection: String): LocalAsrModel? =
    if (runtimeAvailable) localAsrModel(selection) else null
