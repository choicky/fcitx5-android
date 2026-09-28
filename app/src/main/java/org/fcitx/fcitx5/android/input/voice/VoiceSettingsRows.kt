/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

/** What the Android system ASR row offers when tapped, in display order. */
internal enum class SystemAction { Enable, Allow, Disable, Revoke }

/**
 * The status the single System ASR row shows. Enablement and the disclosure answer stay
 * separate (D034): an enabled service that is not allowed yet is not the same as a disabled one.
 */
internal enum class SystemStatus { Disabled, Unavailable, NeedsPermission, Ready, InUse }

internal data class SystemRow(val status: SystemStatus, val actions: List<SystemAction>)

/**
 * The one System ASR row: [enabled] makes it choosable, [authorization] is the disclosure
 * answer, [available] whether the device has a recognizer. Enabling asks for permission when
 * it is missing; permission can be given or withdrawn independently of enablement.
 */
internal fun systemRow(
    enabled: Boolean,
    authorization: SystemAsrAuthorization,
    available: Boolean,
    current: Boolean
): SystemRow {
    val allowed = authorization == SystemAsrAuthorization.Allowed
    val status = when {
        !enabled -> SystemStatus.Disabled
        !available -> SystemStatus.Unavailable
        !allowed -> SystemStatus.NeedsPermission
        current -> SystemStatus.InUse
        else -> SystemStatus.Ready
    }
    val actions = buildList {
        if (!enabled) add(SystemAction.Enable)
        else if (!allowed) add(SystemAction.Allow)
        if (enabled) add(SystemAction.Disable)
        if (allowed) add(SystemAction.Revoke)
    }
    return SystemRow(status, actions)
}

/** The summary under a third-party cloud provider's enable switch. */
internal enum class CloudStatus { NeedsCredentials, EnableToSelect, Selectable, InUse }

/** Without credentials a provider is never presented as usable, enabled or not. */
internal fun cloudStatus(enabled: Boolean, configured: Boolean, current: Boolean) = when {
    !configured -> CloudStatus.NeedsCredentials
    !enabled -> CloudStatus.EnableToSelect
    current -> CloudStatus.InUse
    else -> CloudStatus.Selectable
}

/**
 * The services the current-service dialog lists: enabled and usable now, by the same rules
 * that resolve a session. System ASR that still needs the disclosure answer is listed, since
 * choosing it asks. The saved current service is kept elsewhere even when it is not listed.
 */
internal fun selectableServices(
    candidates: List<AsrServiceId>,
    selection: VoiceSelection,
    local: LocalStatus,
    systemAuthorization: SystemAsrAuthorization,
    systemAvailable: () -> Boolean,
    external: ExternalServices
): List<AsrServiceId> = candidates.filter { service ->
    // Keep legacy services readable in persisted state, but never expose them as choices.
    service !is AsrServiceId.Local || service.model in LocalAsrModel.userVisibleEntries
}.filter { service ->
    selection.isEnabled(service) &&
            when (resolveCurrentService(selection.copy(current = service), local, systemAuthorization, systemAvailable, external)) {
                is AsrResolution.Ready, AsrResolution.NeedsSystemAuthorization -> true
                else -> false
            }
}
