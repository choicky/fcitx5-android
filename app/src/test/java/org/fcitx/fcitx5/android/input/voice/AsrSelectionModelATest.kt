/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.SystemAsrAuthorization.Allowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model A (D055): External Android Voice Input is one top-level configured provider, resolved
 * without a session backend, excluded from recommendation and fallback, and never auto-selected.
 */
class AsrSelectionModelATest {

    private val model = LocalAsrModel.userVisibleEntries.first()
    private val localService = AsrServiceId.Local(model)
    private val localReady = LocalStatus(runtimeAvailable = true, installed = setOf(model))

    // Neither SpeechRecognizer nor any in-IME probe may be touched on the External path (D030/D055).
    private val neverQueried: () -> Boolean = { throw AssertionError("provider availability queried") }

    private fun noExternalConfigured() = object : ExternalServices {
        override fun configured(service: AsrServiceId) = false
        override fun instance(id: String) = null
        override val allowCleartext = false
    }

    @Test
    fun externalIsATopLevelProviderButNotAnInImeService() {
        assertEquals("external", AsrServiceId.External.key)
        assertEquals(AsrServiceId.External, AsrServiceId.parse("external"))
        // excluded from the fixed in-IME service set, so enablement and existing selection stay clean
        assertFalse(AsrServiceId.entries.contains(AsrServiceId.External))
    }

    @Test
    fun externalResolvesToExternalAndroidWithoutAnySessionProbe() {
        val selection = VoiceSelection(AsrServiceId.External, emptySet(), recommendationDone = true)
        // not enabled, no local, no system authorization, availability not queried
        assertEquals(
            AsrResolution.ExternalAndroid,
            resolveCurrentService(selection, localReady, Allowed, neverQueried, noExternalConfigured())
        )
        // resolution never changes the saved provider
        assertEquals(AsrServiceId.External, selection.current)
    }

    @Test
    fun externalStartsBySwitchingAndNeedsNoRecordAudio() {
        val step = voiceStartStep(AsrResolution.ExternalAndroid, recordAudioGranted = false)
        assertEquals(VoiceStartStep.StartExternal, step)
        assertTrue(AsrResolution.ExternalAndroid.offersTrigger)
    }

    @Test
    fun externalNeverFallsBackToAnotherProvider() {
        val selection = VoiceSelection(AsrServiceId.External, setOf(localService), recommendationDone = true)
        assertNull(fallbackTarget(AsrServiceId.External, selection, localReady))
    }

    @Test
    fun externalIsNeverRecommended() {
        // recommend only ever yields in-IME Local/System/Nothing; there is no External variant.
        val selection = VoiceSelection(null, setOf(localService), recommendationDone = false)
        val rec = recommend(localReady, selection, Allowed, { true })
        assertTrue(rec is Recommendation.SelectLocal || rec is Recommendation.SelectSystem)
        // applying a recommendation never sets External
        listOf(
            Recommendation.SelectLocal(model),
            Recommendation.SelectSystem,
            Recommendation.Nothing
        ).forEach { applied ->
            assertNotExternal(selection.applyRecommendation(applied))
        }
    }

    @Test
    fun externalIsAlwaysListedInThePicker() {
        val selection = VoiceSelection(AsrServiceId.External, setOf(localService), recommendationDone = true)
        val candidates = listOf(AsrServiceId.External) + AsrServiceId.entries
        // External is a configured top-level provider: it stays listed even when no external
        // voice IME is discovered; that unavailability is reported at invocation instead.
        val available = selectableServices(
            candidates, selection, localReady, Allowed, { true }, noExternalConfigured()
        )
        assertTrue(available.contains(AsrServiceId.External))
    }

    @Test
    fun pickerKeepsInImeServicesUnderTheExistingRules() {
        val selection = VoiceSelection(AsrServiceId.External, setOf(localService), recommendationDone = true)
        val candidates = listOf(AsrServiceId.External) + AsrServiceId.entries
        val available = selectableServices(
            candidates, selection, localReady, Allowed, { true }, noExternalConfigured()
        )
        // only the enabled and usable in-IME service is listed next to External
        assertEquals(listOf(AsrServiceId.External, localService), available)
    }

    @Test
    fun debugOverrideStillWinsOverExternal() {
        val selection = VoiceSelection(AsrServiceId.External, emptySet(), recommendationDone = true)
        val resolved = resolveVoiceBackend(
            VoiceBackendKind.CaptureProbe, selection, localReady, Allowed, neverQueried
        )
        assertEquals(AsrResolution.Ready(null, VoiceBackendKind.CaptureProbe), resolved)
    }

    @Test
    fun migrationsNeverIntroduceExternal() {
        // no legacy in-IME setting maps to the External top-level provider
        listOf("Auto", "System", "Local", null, "unknown").forEach { legacy ->
            val migrated = migrateLegacyProvider(legacy, Allowed, model)
            assertTrue(migrated.current != AsrServiceId.External)
        }
        // a legacy single-"local" current migrates to a Local model service, never External
        val (current, _) = migrateLocalModels(AsrServiceId.LEGACY_LOCAL_KEY, localEnabled = true, model)
        assertEquals(localService, current)
    }

    private fun assertNotExternal(selection: VoiceSelection) =
        assertTrue("recommendation must not select External", selection.current != AsrServiceId.External)
}
