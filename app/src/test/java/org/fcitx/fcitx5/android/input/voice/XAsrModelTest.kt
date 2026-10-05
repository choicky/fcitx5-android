/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.*
import org.junit.Test

class XAsrModelTest {
    private val models = listOf(LocalAsrModel.XAsrOffline, LocalAsrModel.XAsrStreaming960)

    @Test
    fun installedEnabledXAsrIsManuallySelectableAndRecommended() {
        models.forEach { model ->
            val service = AsrServiceId.Local(model)
            val selection = VoiceSelection(service, setOf(service), recommendationDone = true)
            val local = LocalStatus(true, setOf(model))
            assertEquals(service, AsrServiceId.parse(service.key))
            assertEquals(AsrResolution.Ready(service, VoiceBackendKind.LocalAsr(model)),
                resolveCurrentService(selection, local, SystemAsrAuthorization.Declined, { false }, ExternalServices.None))
            assertEquals(listOf(service), selectableServices(AsrServiceId.entries, selection, local,
                SystemAsrAuthorization.Declined, { false }, ExternalServices.None))
            assertEquals(Recommendation.SelectLocal(model),
                recommend(local, selection, SystemAsrAuthorization.Declined) { throw AssertionError("System ASR queried") })
            assertEquals(VoiceBackendKind.LocalAsr(model), fallbackTarget(AsrServiceId.Doubao, selection, local))
            assertTrue(model.production)
            assertTrue(model.recommendationEligible)
            assertFalse(local.usable(model, selection.withEnabled(service, false)))
            assertFalse(LocalStatus(false, setOf(model)).usable(model, selection))
            assertFalse(LocalStatus(true, emptySet()).usable(model, selection))
        }
    }

    @Test
    fun fallbackUsesTheStableOrderAndSkipsUnavailableModels() {
        val external = AsrServiceId.Doubao
        val services = LocalAsrModel.userVisibleEntries.map(AsrServiceId::Local).toSet() + external
        val allInstalled = LocalStatus(true, LocalAsrModel.entries.toSet())
        val allEnabled = VoiceSelection(external, services, recommendationDone = true)
        assertEquals(
            VoiceBackendKind.LocalAsr(LocalAsrModel.XAsrOffline),
            fallbackTarget(external, allEnabled, allInstalled)
        )

        val offlineDisabled = allEnabled.withEnabled(AsrServiceId.Local(LocalAsrModel.XAsrOffline), false)
        assertEquals(
            VoiceBackendKind.LocalAsr(LocalAsrModel.XAsrStreaming960),
            fallbackTarget(external, offlineDisabled, allInstalled)
        )

        val streamingDisabled = offlineDisabled.withEnabled(AsrServiceId.Local(LocalAsrModel.XAsrStreaming960), false)
        assertEquals(
            VoiceBackendKind.LocalAsr(LocalAsrModel.FunAsrNano),
            fallbackTarget(external, streamingDisabled, allInstalled)
        )

        assertNull(fallbackTarget(external, streamingDisabled, LocalStatus(false, allInstalled.installed)))
        assertNull(fallbackTarget(external, streamingDisabled, LocalStatus(true, emptySet())))
    }

    @Test
    fun archiveRowsUseExistingDownloadAndImportActionsAndVerifiedFourFileCatalog() {
        models.forEach { model ->
            val entry = ModelCatalogEntry.of(model)
            assertEquals(model.requiredFiles.toSet(), entry.files.map { it.path }.toSet())
            assertEquals(4, entry.files.size)
            assertNull(entry.downloadBase)
            assertFalse(entry.licenseAuditComplete)
            assertTrue(entry.downloadOffered(true))
            // D050: the fixed-upstream download is offered in release builds too, not test-only
            assertTrue(entry.downloadOffered(false))
            val row = modelRow(false, null, false, { 0 }, entry.downloadOffered(true), false, false)
            assertEquals(listOf(ModelAction.Download, ModelAction.DownloadFrom, ModelAction.Import, ModelAction.Details), row.actions)
            assertEquals(listOf(ModelAction.Use, ModelAction.Remove, ModelAction.Details),
                modelRow(true, null, false, { 0 }, false, false, false).actions)
        }
    }

    @Test
    fun xAsrTailPaddingPrecedesInputFinishedAndFinalDecode() {
        val calls = mutableListOf<String>()
        val session = StreamingLocalAsrSession(object : StreamingDecoder {
            override fun acceptWaveform(samples: FloatArray) {
                assertEquals(15360, samples.size)
                assertTrue(samples.all { it == 0f })
                calls += "padding"
            }
            override fun inputFinished() { calls += "finished" }
            override fun decodeReady(): String { calls += "decode"; return "tail" }
            override fun release() { calls += "release" }
        }, trailingSilenceSamples = 15360)
        assertEquals("tail", session.finish())
        assertEquals(listOf("padding", "finished", "decode"), calls)
        session.release()
        session.release()
        assertEquals(1, calls.count { it == "release" })
    }

    @Test
    fun cancellingXAsrSessionDoesNotPadOrFinalize() {
        var finishes = 0
        val session = StreamingLocalAsrSession(object : StreamingDecoder {
            override fun acceptWaveform(samples: FloatArray) { fail("cancel must not pad") }
            override fun inputFinished() { finishes++ }
            override fun decodeReady(): String { fail("cancel must not decode"); return "" }
            override fun release() {}
        }, trailingSilenceSamples = 15360)
        session.release()
        assertEquals(0, finishes)
    }
}
