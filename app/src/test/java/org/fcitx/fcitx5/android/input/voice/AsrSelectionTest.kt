/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.Allowed
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.Declined
import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.NotAsked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrSelectionTest {

    // any research model; the tests do not imply a formal Local choice
    private val model = LocalAsrModel.entries.first()
    private val localReady = LocalStatus(runtimeAvailable = true, model = model, filesPresent = true)
    private val noLocal = LocalStatus(runtimeAvailable = true, model = null, filesPresent = false)

    private fun selection(
        current: AsrServiceId?,
        system: Boolean = true,
        local: Boolean = true,
        done: Boolean = true
    ) = VoiceSelection(current, system, local, done)

    private fun resolve(
        selection: VoiceSelection,
        local: LocalStatus = localReady,
        authorization: SystemAsrAuthorization = Allowed,
        systemAvailable: Boolean = true,
        debugOverride: VoiceBackendKind? = null
    ) = resolveVoiceBackend(debugOverride, selection, local, authorization) { systemAvailable }

    /** SpeechRecognizer must not even be queried on these paths (D030). */
    private val systemNotQueried: () -> Boolean = { throw AssertionError("System ASR queried") }

    @Test
    fun selectedLocalIsUsedWithoutQueryingSystem() {
        assertEquals(
            AsrResolution.Ready(AsrServiceId.Local, VoiceBackendKind.LocalAsr(model)),
            resolveCurrentService(selection(AsrServiceId.Local), localReady, NotAsked, systemNotQueried)
        )
    }

    @Test
    fun selectedSystemNeedsAuthorizationAndAvailability() {
        val system = selection(AsrServiceId.System)
        assertEquals(AsrResolution.Ready(AsrServiceId.System, VoiceBackendKind.System), resolve(system))
        assertEquals(AsrResolution.NeedsSystemAuthorization, resolve(system, authorization = NotAsked))
        // an explicit choice asks again after a decline
        assertEquals(AsrResolution.NeedsSystemAuthorization, resolve(system, authorization = Declined))
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.System, UnavailableReason.NoSystemRecognizer),
            resolve(system, systemAvailable = false)
        )
    }

    @Test
    fun localReportsItsOwnUnavailabilityWithoutSwitching() {
        val local = selection(AsrServiceId.Local)
        fun unavailable(reason: UnavailableReason) = AsrResolution.CurrentUnavailable(AsrServiceId.Local, reason)
        assertEquals(unavailable(UnavailableReason.NoLocalModel), resolve(local, noLocal))
        assertEquals(unavailable(UnavailableReason.NoLocalRuntime), resolve(local, localReady.copy(runtimeAvailable = false)))
        assertEquals(unavailable(UnavailableReason.LocalModelFilesMissing), resolve(local, localReady.copy(filesPresent = false)))
    }

    @Test
    fun disabledCurrentServiceIsKeptButUnavailable() {
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.System, UnavailableReason.Disabled),
            resolve(selection(AsrServiceId.System, system = false))
        )
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.Local, UnavailableReason.Disabled),
            resolve(selection(AsrServiceId.Local, local = false))
        )
    }

    @Test
    fun noPersistentAutoAfterTheRecommendation() {
        // nothing selected and the recommendation already ran: no service, not an automatic pick
        assertEquals(AsrResolution.NoService, resolve(selection(null, done = true)))
    }

    @Test
    fun recommendationPicksOnlyAllowedSystemAndNeverResearchLocal() {
        val fresh = selection(null, system = false, local = false, done = false)
        // a ready research model is not recommended (D037)
        assertEquals(
            AsrResolution.NeedsRecommendation(Recommendation.SelectSystem),
            resolve(fresh, localReady, Allowed)
        )
        assertEquals(
            AsrResolution.NeedsRecommendation(Recommendation.AskSystemAuthorization),
            resolve(fresh, localReady, NotAsked)
        )
        assertEquals(AsrResolution.NeedsRecommendation(Recommendation.Nothing), resolve(fresh, authorization = Declined))
        assertEquals(AsrResolution.NeedsRecommendation(Recommendation.Nothing), resolve(fresh, systemAvailable = false))
    }

    @Test
    fun recommendationIsPersistedOnce() {
        val fresh = selection(null, system = false, local = false, done = false)
        val chosen = fresh.applyRecommendation(Recommendation.SelectSystem)
        assertEquals(selection(AsrServiceId.System, system = true, local = false, done = true), chosen)
        // later availability changes do not rerun it or rewrite the selection
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.System, UnavailableReason.NoSystemRecognizer),
            resolve(chosen, systemAvailable = false)
        )
        assertEquals(selection(null, system = false, local = false, done = true), fresh.applyRecommendation(Recommendation.Nothing))
    }

    @Test
    fun disclosureAnswerDuringRecommendation() {
        val fresh = selection(null, system = false, local = false, done = false)
        assertEquals(
            selection(AsrServiceId.System, system = true, local = false, done = true),
            fresh.afterSystemDisclosure(allowed = true)
        )
        assertEquals(selection(null, system = false, local = false, done = true), fresh.afterSystemDisclosure(allowed = false))
        // answering for an explicitly selected service changes no selection
        val explicit = selection(AsrServiceId.System)
        assertEquals(explicit, explicit.afterSystemDisclosure(allowed = false))
    }

    @Test
    fun legacyMigrationNeverSelectsANetworkService() {
        assertEquals(
            VoiceSelection(AsrServiceId.System, systemEnabled = true, localEnabled = false, recommendationDone = true),
            migrateLegacyProvider("System", NotAsked)
        )
        assertEquals(
            VoiceSelection(AsrServiceId.Local, systemEnabled = false, localEnabled = true, recommendationDone = true),
            migrateLegacyProvider("Local", Allowed)
        )
        // old Auto: keep an allowed System, remember a decline, otherwise recommend on first use
        assertEquals(AsrServiceId.System, migrateLegacyProvider("Auto", Allowed).current)
        assertEquals(VoiceSelection(null, false, false, true), migrateLegacyProvider("Auto", Declined))
        assertEquals(VoiceSelection(null, false, false, false), migrateLegacyProvider(null, NotAsked))
        assertEquals(VoiceSelection(null, false, false, false), migrateLegacyProvider("Auto", NotAsked))
    }

    @Test
    fun triggerFollowsResolution() {
        assertTrue(resolve(selection(AsrServiceId.Local), systemAvailable = false).offersTrigger)
        assertTrue(AsrResolution.NeedsSystemAuthorization.offersTrigger)
        assertTrue(AsrResolution.NeedsRecommendation(Recommendation.AskSystemAuthorization).offersTrigger)
        assertFalse(AsrResolution.NeedsRecommendation(Recommendation.Nothing).offersTrigger)
        assertFalse(AsrResolution.NoService.offersTrigger)
        assertFalse(AsrResolution.CurrentUnavailable(AsrServiceId.Local, UnavailableReason.NoLocalModel).offersTrigger)
    }

    @Test
    fun debugOverridePrecedence() {
        assertEquals(VoiceBackendKind.DoubaoAsr, debugBackendOverride(debug = true, doubaoAsr = true, captureProbe = true))
        assertEquals(VoiceBackendKind.CaptureProbe, debugBackendOverride(debug = true, doubaoAsr = false, captureProbe = true))
        assertNull(debugBackendOverride(debug = true, doubaoAsr = false, captureProbe = false))
        assertNull(debugBackendOverride(debug = false, doubaoAsr = true, captureProbe = true))
        assertEquals(
            AsrResolution.Ready(null, VoiceBackendKind.DoubaoAsr),
            resolve(selection(null, done = false), debugOverride = VoiceBackendKind.DoubaoAsr)
        )
    }

    @Test
    fun startOrderDisclosureBeforeMicrophone() {
        val ask = AsrResolution.NeedsRecommendation(Recommendation.AskSystemAuthorization)
        assertEquals(VoiceStartStep.RequestSystemAuthorization, voiceStartStep(ask, recordAudioGranted = false))
        assertEquals(
            VoiceStartStep.ApplyRecommendation(Recommendation.SelectSystem),
            voiceStartStep(AsrResolution.NeedsRecommendation(Recommendation.SelectSystem), false)
        )
        val ready = AsrResolution.Ready(AsrServiceId.System, VoiceBackendKind.System)
        assertEquals(VoiceStartStep.RequestRecordAudio, voiceStartStep(ready, false))
        assertEquals(VoiceStartStep.Start(VoiceBackendKind.System), voiceStartStep(ready, true))
        assertEquals(VoiceStartStep.Unavailable(AsrResolution.NoService), voiceStartStep(AsrResolution.NoService, false))
    }

    @Test
    fun systemAsrNeverStartsWithoutAuthorization() {
        val currents = listOf(null, AsrServiceId.System, AsrServiceId.Local)
        for (current in currents) for (done in listOf(true, false)) for (auth in listOf(NotAsked, Declined))
            for (local in listOf(localReady, noLocal)) for (available in listOf(true, false)) for (granted in listOf(true, false)) {
                val step = voiceStartStep(resolve(selection(current, done = done), local, auth, available), granted)
                assertFalse(step == VoiceStartStep.Start(VoiceBackendKind.System))
            }
    }

    @Test
    fun serviceKeysRoundTrip() {
        AsrServiceId.entries.forEach { assertEquals(it, AsrServiceId.parse(it.key)) }
        assertNull(AsrServiceId.parse("auto"))
    }

    @Test
    fun onlyExternalServicesFallBackAndNeverToResearchModels() {
        // selected Local or System report their own failure (D035)
        AsrServiceId.entries.forEach { assertNull(fallbackTarget(it, selection(it), localReady)) }
        assertNull(fallbackTarget(null, selection(null), localReady))
        // A and B are research models (D037), never fallback targets
        LocalAsrModel.entries.forEach { assertFalse(it.production) }
    }
}
