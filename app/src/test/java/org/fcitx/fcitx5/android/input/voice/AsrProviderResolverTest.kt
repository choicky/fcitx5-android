/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.AsrProvider.Auto
import org.fcitx.fcitx5.android.input.voice.AsrProvider.Local
import org.fcitx.fcitx5.android.input.voice.AsrProvider.System
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.Allowed
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.Declined
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.NotAsked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrProviderResolverTest {

    // any configured Local model; the tests do not imply a formal Local choice
    private val model = LocalAsrModel.entries.first()
    private val local = AsrResolution.Ready(VoiceBackendKind.LocalAsr(model))
    private val system = AsrResolution.Ready(VoiceBackendKind.System)

    private fun resolve(
        configured: AsrProvider,
        usableLocal: LocalAsrModel? = null,
        authorization: SystemAsrAuthorization = NotAsked,
        systemAvailable: Boolean = true,
        debugOverride: VoiceBackendKind? = null
    ) = resolveVoiceBackend(debugOverride, configured, usableLocal, authorization) { systemAvailable }

    /** SpeechRecognizer must not even be queried on these paths (D030). */
    private val systemNotQueried: () -> Boolean = { throw AssertionError("System ASR queried") }

    @Test
    fun autoPrefersUsableLocalOverSystem() {
        assertEquals(local, resolve(Auto, usableLocal = model, authorization = Allowed))
        assertEquals(
            local,
            resolveVoiceBackend(null, Auto, model, Allowed, systemNotQueried)
        )
    }

    @Test
    fun autoUsesAuthorizedAvailableSystemWithoutLocal() {
        assertEquals(system, resolve(Auto, authorization = Allowed))
    }

    @Test
    fun autoNeverUsesUnauthorizedSystem() {
        // not asked yet: ask first, do not start System ASR
        assertEquals(AsrResolution.NeedsSystemAuthorization, resolve(Auto, authorization = NotAsked))
        // declined: nothing usable, and no repeated prompt
        assertEquals(AsrResolution.NoProvider, resolve(Auto, authorization = Declined))
    }

    @Test
    fun autoWithNothingUsableNeedsConfiguration() {
        assertEquals(AsrResolution.NoProvider, resolve(Auto, authorization = Allowed, systemAvailable = false))
        assertEquals(AsrResolution.NoProvider, resolve(Auto, authorization = NotAsked, systemAvailable = false))
    }

    @Test
    fun autoNeverPicksDebugOrNetworkBackends() {
        val backends = SystemAsrAuthorization.entries.flatMap { auth ->
            listOf(true, false).flatMap { available ->
                listOf(null, model).map { resolve(Auto, it, auth, available) }
            }
        }.filterIsInstance<AsrResolution.Ready>().map { it.backend }
        assertTrue(backends.all { it == VoiceBackendKind.System || it is VoiceBackendKind.LocalAsr })
    }

    @Test
    fun explicitSystemRequiresAuthorization() {
        assertEquals(AsrResolution.NeedsSystemAuthorization, resolve(System, authorization = NotAsked))
        // an explicit choice asks again after a decline instead of using System ASR
        assertEquals(AsrResolution.NeedsSystemAuthorization, resolve(System, authorization = Declined))
        assertEquals(system, resolve(System, authorization = Allowed))
    }

    @Test
    fun explicitSystemUnavailable() {
        assertEquals(AsrResolution.SystemUnavailable, resolve(System, authorization = Allowed, systemAvailable = false))
        assertEquals(AsrResolution.SystemUnavailable, resolve(System, authorization = NotAsked, systemAvailable = false))
    }

    @Test
    fun explicitSystemIgnoresLocal() {
        assertEquals(system, resolve(System, usableLocal = model, authorization = Allowed))
    }

    @Test
    fun explicitLocalDoesNotFallBackToSystem() {
        assertEquals(AsrResolution.LocalUnavailable, resolve(Local, authorization = Allowed))
        assertEquals(AsrResolution.LocalUnavailable, resolveVoiceBackend(null, Local, null, Allowed, systemNotQueried))
        assertEquals(local, resolveVoiceBackend(null, Local, model, NotAsked, systemNotQueried))
    }

    @Test
    fun debugOverridePrecedence() {
        // Doubao, then the capture probe; release builds never override
        assertEquals(VoiceBackendKind.DoubaoAsr, debugBackendOverride(debug = true, doubaoAsr = true, captureProbe = true))
        assertEquals(VoiceBackendKind.CaptureProbe, debugBackendOverride(debug = true, doubaoAsr = false, captureProbe = true))
        assertNull(debugBackendOverride(debug = true, doubaoAsr = false, captureProbe = false))
        assertNull(debugBackendOverride(debug = false, doubaoAsr = true, captureProbe = true))
    }

    @Test
    fun debugOverrideBypassesFormalProvider() {
        val doubao = AsrResolution.Ready(VoiceBackendKind.DoubaoAsr)
        AsrProvider.entries.forEach {
            assertEquals(doubao, resolveVoiceBackend(VoiceBackendKind.DoubaoAsr, it, model, Declined, systemNotQueried))
        }
        // without an override the formal provider decides
        assertEquals(local, resolve(Auto, usableLocal = model, debugOverride = null))
    }

    @Test
    fun triggerFollowsResolutionNotSpeechRecognizer() {
        // a usable Local ASR offers the trigger although System ASR is unavailable
        assertTrue(resolveVoiceBackend(null, Auto, model, NotAsked, systemNotQueried).offersTrigger)
        assertTrue(resolve(Local, usableLocal = model, systemAvailable = false).offersTrigger)
        // tapping it can ask for System ASR authorization
        assertTrue(AsrResolution.NeedsSystemAuthorization.offersTrigger)
        assertTrue(system.offersTrigger)
        listOf(AsrResolution.NoProvider, AsrResolution.LocalUnavailable, AsrResolution.SystemUnavailable)
            .forEach { assertFalse(it.offersTrigger) }
    }

    @Test
    fun authorizationState() {
        assertEquals(NotAsked, SystemAsrAuthorization.of(allowed = false, answered = false))
        assertEquals(Declined, SystemAsrAuthorization.of(allowed = false, answered = true))
        assertEquals(Allowed, SystemAsrAuthorization.of(allowed = true, answered = true))
        // allowed from the settings switch without the dialog
        assertEquals(Allowed, SystemAsrAuthorization.of(allowed = true, answered = false))
    }

    @Test
    fun disclosureComesBeforeMicrophonePermission() {
        // first use with Auto, no Local model, nothing granted: the disclosure is shown first
        val firstTap = voiceStartStep(resolve(Auto, authorization = NotAsked), recordAudioGranted = false)
        assertEquals(VoiceStartStep.RequestSystemAuthorization, firstTap)
        // once allowed, the microphone is the only thing left
        assertEquals(
            VoiceStartStep.RequestRecordAudio,
            voiceStartStep(resolve(Auto, authorization = Allowed), recordAudioGranted = false)
        )
        assertEquals(
            VoiceStartStep.Start(VoiceBackendKind.System),
            voiceStartStep(resolve(Auto, authorization = Allowed), recordAudioGranted = true)
        )
    }

    @Test
    fun noMicrophonePromptWhenNothingIsUsable() {
        listOf(
            resolve(Auto, authorization = Declined),
            resolve(Local),
            resolve(System, authorization = Allowed, systemAvailable = false)
        ).forEach {
            assertEquals(VoiceStartStep.Unavailable(it), voiceStartStep(it, recordAudioGranted = false))
        }
    }

    @Test
    fun systemAsrNeverStartsWithoutAuthorization() {
        for (configured in AsrProvider.entries)
            for (auth in listOf(NotAsked, Declined))
                for (usable in listOf(null, model))
                    for (available in listOf(true, false))
                        for (granted in listOf(true, false)) {
                            val step = voiceStartStep(resolve(configured, usable, auth, available), granted)
                            assertFalse(step == VoiceStartStep.Start(VoiceBackendKind.System))
                        }
    }

    @Test
    fun localModelNeedsARuntime() {
        // release builds ship no Local ASR runtime
        assertNull(configuredLocalModel(runtimeAvailable = false, selection = model.name))
        assertEquals(model, configuredLocalModel(runtimeAvailable = true, selection = model.name))
        assertNull(configuredLocalModel(runtimeAvailable = true, selection = "Off"))
    }
}
