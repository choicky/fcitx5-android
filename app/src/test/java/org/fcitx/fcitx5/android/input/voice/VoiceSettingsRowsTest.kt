/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.SystemAction.Allow
import org.fcitx.fcitx5.android.input.voice.SystemAction.Disable
import org.fcitx.fcitx5.android.input.voice.SystemAction.Enable
import org.fcitx.fcitx5.android.input.voice.SystemAction.Revoke
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.Allowed
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.Declined
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.NotAsked
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSettingsRowsTest {

    // --- the single System ASR row ---

    private fun system(
        enabled: Boolean,
        authorization: SystemAsrAuthorization,
        available: Boolean = true,
        current: Boolean = false
    ) = systemRow(enabled, authorization, available, current)

    @Test
    fun disabledAndUnpermittedAreDistinctStates() {
        // off, never asked: turning it on is the only step (it asks for permission)
        assertEquals(SystemRow(SystemStatus.Disabled, listOf(Enable)), system(false, NotAsked))
        // on but not allowed (never asked, or declined): permission is the next step
        assertEquals(SystemRow(SystemStatus.NeedsPermission, listOf(Allow, Disable)), system(true, NotAsked))
        assertEquals(SystemRow(SystemStatus.NeedsPermission, listOf(Allow, Disable)), system(true, Declined))
        // allowed but off: still off, and the permission can be withdrawn
        assertEquals(SystemRow(SystemStatus.Disabled, listOf(Enable, Revoke)), system(false, Allowed))
    }

    @Test
    fun enabledAndAllowedIsReadyAndInUseWhenCurrent() {
        assertEquals(SystemRow(SystemStatus.Ready, listOf(Disable, Revoke)), system(true, Allowed))
        assertEquals(SystemStatus.InUse, system(true, Allowed, current = true).status)
        // a current System service that is not allowed is not shown as in use
        assertEquals(SystemStatus.NeedsPermission, system(true, Declined, current = true).status)
        // nor a disabled one
        assertEquals(SystemStatus.Disabled, system(false, Allowed, current = true).status)
    }

    @Test
    fun aDeviceWithoutRecognizerSaysSoButKeepsTheControls() {
        assertEquals(SystemRow(SystemStatus.Unavailable, listOf(Allow, Disable)), system(true, NotAsked, available = false))
        assertEquals(SystemRow(SystemStatus.Unavailable, listOf(Disable, Revoke)), system(true, Allowed, available = false))
    }

    // --- cloud provider switches ---

    @Test
    fun aProviderWithoutCredentialsIsNeverPresentedAsUsable() {
        assertEquals(CloudStatus.NeedsCredentials, cloudStatus(enabled = false, configured = false, current = false))
        assertEquals(CloudStatus.NeedsCredentials, cloudStatus(enabled = true, configured = false, current = false))
        assertEquals(CloudStatus.NeedsCredentials, cloudStatus(enabled = true, configured = false, current = true))
    }

    @Test
    fun aConfiguredProviderIsSelectableOnceEnabled() {
        assertEquals(CloudStatus.EnableToSelect, cloudStatus(enabled = false, configured = true, current = false))
        assertEquals(CloudStatus.Selectable, cloudStatus(enabled = true, configured = true, current = false))
        assertEquals(CloudStatus.InUse, cloudStatus(enabled = true, configured = true, current = true))
        // selected but switched off: not in use
        assertEquals(CloudStatus.EnableToSelect, cloudStatus(enabled = false, configured = true, current = true))
    }

    // --- the current-service dialog ---

    private val a = AsrServiceId.Local(LocalAsrModel.ZipformerZh)
    private val b = AsrServiceId.Local(LocalAsrModel.FunAsrNano)
    private val c = AsrServiceId.Local(LocalAsrModel.ZipformerBilingual)
    private val secure = SelfHostedInstance("s1", "home", SelfHostedProtocol.SherpaOnnx, "wss://asr.example.org/ws")
    private val cleartext = SelfHostedInstance("s2", "lan", SelfHostedProtocol.SherpaOnnx, "ws://192.168.1.2/ws")
    private val candidates = AsrServiceId.entries + listOf(secure.service, cleartext.service)

    private fun external(vararg configured: AsrServiceId) = object : ExternalServices {
        override fun configured(service: AsrServiceId) = service in configured
        override fun instance(id: String) = listOf(secure, cleartext).firstOrNull { it.id == id }
        override val allowCleartext = false
    }

    private fun selectable(
        enabled: Set<AsrServiceId>,
        installed: Set<LocalAsrModel> = emptySet(),
        authorization: SystemAsrAuthorization = Allowed,
        systemAvailable: Boolean = true,
        external: ExternalServices = external(),
        current: AsrServiceId? = null
    ) = selectableServices(
        candidates,
        VoiceSelection(current, enabled, recommendationDone = true),
        LocalStatus(runtimeAvailable = true, installed = installed),
        authorization,
        { systemAvailable },
        external
    )

    @Test
    fun onlyEnabledUsableServicesAreListed() {
        val all = candidates.toSet()
        assertEquals(
            listOf(AsrServiceId.System, c, AsrServiceId.Qwen, secure.service),
            selectable(
                all,
                installed = setOf(LocalAsrModel.ZipformerBilingual),
                external = external(AsrServiceId.Qwen)
            )
        )
        // nothing enabled, nothing listed, even when everything is configured and installed
        assertEquals(
            emptyList<AsrServiceId>(),
            selectable(emptySet(), LocalAsrModel.userVisibleEntries.toSet(), external = external(AsrServiceId.Doubao))
        )
    }

    @Test
    fun retiredAIsExcludedEvenWhenACallerStillHasItsLegacyCandidate() {
        assertEquals(
            listOf(c),
            selectable(setOf(a, c), installed = setOf(LocalAsrModel.ZipformerZh, LocalAsrModel.ZipformerBilingual))
        )
    }

    @Test
    fun localCatalogIsOrderedCThenB() {
        assertEquals(
            listOf(LocalAsrModel.ZipformerBilingual, LocalAsrModel.FunAsrNano),
            ModelCatalogEntry.entries.map { it.model }
        )
    }

    @Test
    fun anEnabledProviderWithoutCredentialsIsNotListed() {
        assertEquals(emptyList<AsrServiceId>(), selectable(setOf(AsrServiceId.Doubao, AsrServiceId.Tencent)))
        assertEquals(
            listOf(AsrServiceId.Tencent),
            selectable(setOf(AsrServiceId.Doubao, AsrServiceId.Tencent), external = external(AsrServiceId.Tencent))
        )
    }

    @Test
    fun localModelsNeedToBeInstalledAndEnabledEachOnTheirOwn() {
        // enabled but not installed: not listed; installed but not enabled: not listed
        assertEquals(listOf(c), selectable(setOf(b, c), installed = setOf(LocalAsrModel.ZipformerBilingual)))
    }

    @Test
    fun systemNeedingPermissionIsListedButNotWithoutRecognizer() {
        // choosing it asks for permission, so it is offered
        assertEquals(listOf(AsrServiceId.System), selectable(setOf(AsrServiceId.System), authorization = NotAsked))
        assertEquals(listOf(AsrServiceId.System), selectable(setOf(AsrServiceId.System), authorization = Declined))
        assertEquals(emptyList<AsrServiceId>(), selectable(setOf(AsrServiceId.System), systemAvailable = false))
    }

    @Test
    fun anUnusableCurrentServiceIsNotListedAndStaysSelected() {
        val selection = VoiceSelection(c, setOf(c, AsrServiceId.Doubao), recommendationDone = true)
        // C was removed, Doubao lost its credentials
        val local = LocalStatus(runtimeAvailable = true, installed = emptySet())
        val listed = selectableServices(candidates, selection, local, Allowed, { true }, external())
        assertEquals(emptyList<AsrServiceId>(), listed)
        // the current row explains why instead of another service being chosen
        assertEquals(
            AsrResolution.CurrentUnavailable(c, UnavailableReason.LocalModelFilesMissing),
            resolveCurrentService(selection, local, Allowed, { true }, external())
        )
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.Doubao, UnavailableReason.MissingCredentials),
            resolveCurrentService(selection.copy(current = AsrServiceId.Doubao), local, Allowed, { true }, external())
        )
        assertEquals(
            AsrResolution.CurrentUnavailable(b, UnavailableReason.Disabled),
            resolveCurrentService(selection.copy(current = b), local, Allowed, { true }, external())
        )
    }

    @Test
    fun aSelfHostedServerWithAnUnacceptedAddressIsNotListed() {
        assertEquals(listOf(secure.service), selectable(setOf(secure.service, cleartext.service)))
    }
}
