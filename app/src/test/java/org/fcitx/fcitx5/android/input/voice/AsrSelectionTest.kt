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

    // retained Local model used for common selection tests
    private val model = LocalAsrModel.userVisibleEntries.first()
    private val localService = AsrServiceId.Local(model)
    private val localReady = LocalStatus(runtimeAvailable = true, installed = setOf(model))
    private val noLocal = LocalStatus(runtimeAvailable = true, installed = emptySet())

    private fun selection(
        current: AsrServiceId?,
        system: Boolean = true,
        local: Boolean = true,
        done: Boolean = true
    ) = VoiceSelection(
        current,
        buildSet {
            if (system) add(AsrServiceId.System)
            if (local) add(localService)
        },
        done
    )

    private fun resolve(
        selection: VoiceSelection,
        local: LocalStatus = localReady,
        authorization: SystemAsrAuthorization = Allowed,
        systemAvailable: Boolean = true,
        debugOverride: VoiceBackendKind? = null
    ) = resolveVoiceBackend(debugOverride, selection, local, authorization, systemAvailable = { systemAvailable })

    private fun configured(vararg services: AsrServiceId, instances: List<SelfHostedInstance> = emptyList(), cleartext: Boolean = false) =
        object : ExternalServices {
            override fun configured(service: AsrServiceId) = service in services
            override fun instance(id: String) = instances.firstOrNull { it.id == id }
            override val allowCleartext = cleartext
        }

    /** SpeechRecognizer must not even be queried on these paths (D030). */
    private val systemNotQueried: () -> Boolean = { throw AssertionError("System ASR queried") }

    @Test
    fun selectedLocalIsUsedWithoutQueryingSystem() {
        assertEquals(
            AsrResolution.Ready(localService, VoiceBackendKind.LocalAsr(model)),
            resolveCurrentService(selection(localService), localReady, NotAsked, systemNotQueried)
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
        val local = selection(localService)
        fun unavailable(reason: UnavailableReason) = AsrResolution.CurrentUnavailable(localService, reason)
        assertEquals(unavailable(UnavailableReason.LocalModelFilesMissing), resolve(local, noLocal))
        assertEquals(unavailable(UnavailableReason.NoLocalRuntime), resolve(local, localReady.copy(runtimeAvailable = false)))
    }

    @Test
    fun disabledCurrentServiceIsKeptButUnavailable() {
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.System, UnavailableReason.Disabled),
            resolve(selection(AsrServiceId.System, system = false))
        )
        assertEquals(
            AsrResolution.CurrentUnavailable(localService, UnavailableReason.Disabled),
            resolve(selection(localService, local = false))
        )
    }

    @Test
    fun recommendationRemainsAvailableWhenCurrentIsCleared() {
        // recommendationDone records history but must not hide a user-initiated rerun
        assertEquals(
            AsrResolution.NeedsRecommendation(Recommendation.Nothing),
            resolve(selection(null, system = false, local = false, done = true), noLocal, Declined)
        )
    }

    @Test
    fun recommendationUsesSystemWhenNoUsableLocalExists() {
        val fresh = selection(null, system = false, local = false, done = false)
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
    fun recommendationPrefersFunAsrNanoAndDoesNotPromoteManualXAsr() {
        val nano = AsrServiceId.Local(LocalAsrModel.FunAsrNano)
        val offline = AsrServiceId.Local(LocalAsrModel.XAsrOffline)
        val both = VoiceSelection(null, setOf(nano, offline), true)
        val all = LocalStatus(true, setOf(LocalAsrModel.FunAsrNano, LocalAsrModel.XAsrOffline))
        assertEquals(
            AsrResolution.NeedsRecommendation(Recommendation.SelectLocal(LocalAsrModel.FunAsrNano)),
            resolveCurrentService(both, all, Allowed, systemNotQueried)
        )
        val offlineOnly = both.copy(enabled = setOf(offline))
        assertEquals(AsrResolution.NeedsRecommendation(Recommendation.SelectSystem),
            resolveCurrentService(offlineOnly, all, Allowed, { true }))
        assertEquals(
            VoiceSelection(nano, setOf(nano, offline), true),
            both.applyRecommendation(Recommendation.SelectLocal(LocalAsrModel.FunAsrNano))
        )
    }

    @Test
    fun recommendationIsPersistedOnce() {
        val fresh = selection(null, system = false, local = false, done = false)
        val chosen = fresh.applyRecommendation(Recommendation.SelectSystem)
        assertEquals(selection(AsrServiceId.System, system = true, local = false, done = true), chosen)
        // an explicit selection remains stable when availability changes
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
            VoiceSelection(AsrServiceId.System, setOf(AsrServiceId.System), true),
            migrateLegacyProvider("System", NotAsked)
        )
        // an old Local becomes the Developer screen's research model, or nothing without one
        assertEquals(
            VoiceSelection(localService, setOf(localService), true),
            migrateLegacyProvider("Local", Allowed, model)
        )
        assertEquals(VoiceSelection(null, emptySet(), true), migrateLegacyProvider("Local", Allowed))
        // old Auto: keep an allowed System, remember a decline, otherwise recommend on first use
        assertEquals(AsrServiceId.System, migrateLegacyProvider("Auto", Allowed).current)
        assertEquals(VoiceSelection(null, emptySet(), true), migrateLegacyProvider("Auto", Declined))
        assertEquals(VoiceSelection(null, emptySet(), false), migrateLegacyProvider(null, NotAsked))
        assertEquals(VoiceSelection(null, emptySet(), false), migrateLegacyProvider("Auto", NotAsked))
    }

    @Test
    fun triggerFollowsResolution() {
        assertTrue(resolve(selection(localService), systemAvailable = false).offersTrigger)
        assertTrue(AsrResolution.NeedsSystemAuthorization.offersTrigger)
        assertTrue(AsrResolution.NeedsRecommendation(Recommendation.AskSystemAuthorization).offersTrigger)
        assertFalse(AsrResolution.NeedsRecommendation(Recommendation.Nothing).offersTrigger)
        assertFalse(AsrResolution.NoService.offersTrigger)
        assertFalse(AsrResolution.CurrentUnavailable(localService, UnavailableReason.LocalModelFilesMissing).offersTrigger)
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
        val currents = listOf(null, AsrServiceId.System, localService)
        for (current in currents) for (done in listOf(true, false)) for (auth in listOf(NotAsked, Declined))
            for (local in listOf(localReady, noLocal)) for (available in listOf(true, false)) for (granted in listOf(true, false)) {
                val step = voiceStartStep(resolve(selection(current, done = done), local, auth, available), granted)
                assertFalse(step == VoiceStartStep.Start(VoiceBackendKind.System))
            }
    }

    @Test
    fun serviceKeysRoundTrip() {
        AsrServiceId.entries.forEach { assertEquals(it, AsrServiceId.parse(it.key)) }
        assertNull(AsrServiceId.parse("local:ZipformerZh"))
        assertNull(AsrServiceId.parse("auto"))
    }

    @Test
    fun onlyExternalServicesFallBackAndNeverToSystemOrLocal() {
        // selected Local or System report their own failure (D035)
        AsrServiceId.entries.forEach { assertNull(fallbackTarget(it, selection(it), localReady)) }
        assertNull(fallbackTarget(null, selection(null), localReady))
        assertTrue(LocalAsrModel.entries.all { it.production })
    }

    @Test
    fun doubaoNeedsItsOwnCredentials() {
        val doubao = VoiceSelection(AsrServiceId.Doubao, setOf(AsrServiceId.Doubao), true)
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.Doubao, UnavailableReason.MissingCredentials),
            resolveCurrentService(doubao, localReady, Allowed, systemNotQueried)
        )
        assertEquals(
            AsrResolution.Ready(AsrServiceId.Doubao, VoiceBackendKind.Doubao),
            resolveCurrentService(doubao, localReady, NotAsked, systemNotQueried, configured(AsrServiceId.Doubao))
        )
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.Doubao, UnavailableReason.Disabled),
            resolveCurrentService(doubao.withEnabled(AsrServiceId.Doubao, false), localReady, Allowed, systemNotQueried, configured(AsrServiceId.Doubao))
        )
    }

    @Test
    fun externalServiceFallsBackOnlyToAProductionLocalModel() {
        val doubao = VoiceSelection(AsrServiceId.Doubao, setOf(AsrServiceId.Doubao, localService), true)
        assertEquals(VoiceBackendKind.LocalAsr(model), fallbackTarget(AsrServiceId.Doubao, doubao, localReady))
        // never to System, even when System is enabled and allowed
        assertNull(fallbackTarget(AsrServiceId.Doubao, doubao.withEnabled(AsrServiceId.System, true), noLocal))
    }

    @Test
    fun eachLocalModelIsItsOwnService() {
        val services = LocalAsrModel.userVisibleEntries.map(AsrServiceId::Local)
        assertTrue(AsrServiceId.entries.containsAll(services))
        services.forEach { assertEquals(it, AsrServiceId.parse(it.key)) }
        assertEquals(LocalAsrModel.userVisibleEntries.size, services.map { it.key }.toSet().size)
        // the single Local service's key is only read by the migration
        assertNull(AsrServiceId.parse(AsrServiceId.LEGACY_LOCAL_KEY))
    }

    @Test
    fun onlyAnEnabledInstalledModelIsUsable() {
        val (a, b) = LocalAsrModel.userVisibleEntries
        val selection = VoiceSelection(null, setOf(AsrServiceId.Local(a), AsrServiceId.Local(b)), true)
        val status = LocalStatus(runtimeAvailable = true, installed = setOf(a))
        assertTrue(status.usable(a, selection))
        // enabled but not installed (downloading, partial, failed verification, removed)
        assertFalse(status.usable(b, selection))
        // installed but not enabled
        assertFalse(status.usable(a, VoiceSelection(null, emptySet(), true)))
        // a build without the runtime
        assertFalse(status.copy(runtimeAvailable = false).usable(a, selection))
    }

    @Test
    fun theCurrentModelRunsAloneAndOthersDoNotStandIn() {
        val (a, b) = LocalAsrModel.userVisibleEntries
        val both = VoiceSelection(AsrServiceId.Local(b), setOf(AsrServiceId.Local(a), AsrServiceId.Local(b)), true)
        val onlyA = LocalStatus(runtimeAvailable = true, installed = setOf(a))
        // B selected but removed: reported, not replaced by the installed and enabled A
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.Local(b), UnavailableReason.LocalModelFilesMissing),
            resolve(both, onlyA)
        )
        assertEquals(
            AsrResolution.Ready(AsrServiceId.Local(a), VoiceBackendKind.LocalAsr(a)),
            resolve(both.copy(current = AsrServiceId.Local(a)), onlyA)
        )
        // disabling the current model keeps it selected and says so
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.Local(a), UnavailableReason.Disabled),
            resolve(both.copy(current = AsrServiceId.Local(a)).withEnabled(AsrServiceId.Local(a), false), onlyA)
        )
        val all = LocalStatus(runtimeAvailable = true, installed = LocalAsrModel.entries.toSet())
        val qwen = VoiceSelection(AsrServiceId.Qwen, setOf(AsrServiceId.Qwen) + LocalAsrModel.userVisibleEntries.map(AsrServiceId::Local), true)
        assertEquals(VoiceBackendKind.LocalAsr(LocalAsrModel.XAsrOffline), fallbackTarget(AsrServiceId.Qwen, qwen, all))
    }

    @Test
    fun migrationFromTheSingleLocalServiceKeepsTheEffectiveConfiguration() {
        val (a, b) = LocalAsrModel.userVisibleEntries
        // enabled Local with a model, selected: that model, enabled and selected
        assertEquals(AsrServiceId.Local(b) to setOf<AsrServiceId>(AsrServiceId.Local(b)), migrateLocalModels("local", true, b))
        // Local enabled with a model, another service selected: only the model is enabled
        assertEquals(AsrServiceId.Doubao to setOf<AsrServiceId>(AsrServiceId.Local(a)), migrateLocalModels("doubao", true, a))
        // Local disabled: nothing enabled; a current Local stays selected and shows as disabled
        assertEquals(AsrServiceId.Local(a) to emptySet<AsrServiceId>(), migrateLocalModels("local", false, a))
        // Local selected without a model could not run: no selection, nothing enabled
        assertEquals(null to emptySet<AsrServiceId>(), migrateLocalModels("local", true, null))
        assertEquals(null to emptySet<AsrServiceId>(), migrateLocalModels("", false, null))
        // other models are never enabled by the migration
        assertEquals(setOf<AsrServiceId>(AsrServiceId.Local(a)), migrateLocalModels("system", true, a).second)
    }

    @Test
    fun neverSelectsDoubaoWithoutTheUser() {
        // the recommendation and the migration never pick a network service
        for (auth in SystemAsrAuthorization.entries) for (legacy in listOf(null, "Auto", "System", "Local", "bogus")) {
            val migrated = migrateLegacyProvider(legacy, auth, model)
            assertFalse(migrated.current == AsrServiceId.Doubao)
            assertFalse(AsrServiceId.Doubao in migrated.enabled)
            val recommended = migrated.applyRecommendation(recommend(noLocal, migrated, auth) { true })
            assertFalse(recommended.current == AsrServiceId.Doubao)
        }
    }

    @Test
    fun selfHostedInstanceResolutionAndEndpointPolicy() {
        val secure = SelfHostedInstance("a1", "home", SelfHostedProtocol.SherpaOnnx, "wss://asr.example.org/ws")
        val plain = secure.copy(id = "b2", url = "ws://192.168.1.2:6006")
        val selection = VoiceSelection(secure.service, setOf(secure.service, plain.service), true)
        val ext = configured(instances = listOf(secure, plain))
        assertEquals(
            AsrResolution.Ready(secure.service, VoiceBackendKind.SelfHosted(secure)),
            resolveCurrentService(selection, localReady, NotAsked, systemNotQueried, ext)
        )
        // plain ws:// is refused unless cleartext is explicitly allowed (debug builds only)
        val onPlain = selection.copy(current = plain.service)
        assertEquals(
            AsrResolution.CurrentUnavailable(plain.service, UnavailableReason.CleartextEndpoint),
            resolveCurrentService(onPlain, localReady, NotAsked, systemNotQueried, ext)
        )
        assertEquals(
            AsrResolution.Ready(plain.service, VoiceBackendKind.SelfHosted(plain)),
            resolveCurrentService(onPlain, localReady, NotAsked, systemNotQueried, configured(instances = listOf(plain), cleartext = true))
        )
        // a removed instance keeps the saved selection but is unavailable
        assertEquals(
            AsrResolution.CurrentUnavailable(secure.service, UnavailableReason.InstanceMissing),
            resolveCurrentService(selection, localReady, NotAsked, systemNotQueried, configured())
        )
    }

    @Test
    fun endpointValidation() {
        assertNull(endpointProblem("wss://h:443/path", allowCleartext = false))
        assertEquals(EndpointProblem.Cleartext, endpointProblem("ws://h", allowCleartext = false))
        assertNull(endpointProblem("ws://h", allowCleartext = true))
        assertEquals(EndpointProblem.Invalid, endpointProblem("https://h", allowCleartext = true))
        assertEquals(EndpointProblem.Invalid, endpointProblem("wss://", allowCleartext = true))
        assertEquals(EndpointProblem.Invalid, endpointProblem("not a url", allowCleartext = true))
    }

    @Test
    fun selfHostedKeysAndInstanceStorage() {
        val s = AsrServiceId.SelfHosted("abc123")
        assertEquals(s, AsrServiceId.parse(s.key))
        assertNull(AsrServiceId.parse("selfhosted:../x"))
        val list = listOf(SelfHostedInstance("abc123", "家里的服务器", SelfHostedProtocol.SherpaOnnx, "wss://h/ws"))
        assertEquals(list, SelfHostedInstance.decode(SelfHostedInstance.encode(list)))
        // unknown protocol entries are dropped, not fatal
        assertEquals(emptyList<SelfHostedInstance>(), SelfHostedInstance.decode("""[{"id":"x1","protocol":"future","url":"wss://h"}]"""))
        assertEquals(emptyList<SelfHostedInstance>(), SelfHostedInstance.decode("garbage"))
    }

    @Test
    fun selfHostedFallsBackOnlyToProductionLocal() {
        val s = AsrServiceId.SelfHosted("abc123")
        assertEquals(VoiceBackendKind.LocalAsr(model), fallbackTarget(s, VoiceSelection(s, setOf(s, localService, AsrServiceId.System), true), localReady))
    }

    @Test
    fun qwenIsAnIndependentManagedCloudService() {
        val qwen = VoiceSelection(AsrServiceId.Qwen, setOf(AsrServiceId.Qwen, AsrServiceId.Doubao), true)
        // Doubao's credentials do not configure Qwen
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.Qwen, UnavailableReason.MissingCredentials),
            resolveCurrentService(qwen, localReady, Allowed, systemNotQueried, configured(AsrServiceId.Doubao))
        )
        assertEquals(
            AsrResolution.Ready(AsrServiceId.Qwen, VoiceBackendKind.Qwen),
            resolveCurrentService(qwen, localReady, Allowed, systemNotQueried, configured(AsrServiceId.Qwen))
        )
        // no external-to-external fallback: Qwen falls back only to a production Local model
        assertNull(fallbackTarget(AsrServiceId.Qwen, qwen, localReady))
    }

    @Test
    fun tencentIsAnIndependentManagedCloudService() {
        val tencent = VoiceSelection(AsrServiceId.Tencent, setOf(AsrServiceId.Tencent), true)
        assertEquals(
            AsrResolution.CurrentUnavailable(AsrServiceId.Tencent, UnavailableReason.MissingCredentials),
            resolveCurrentService(tencent, localReady, Allowed, systemNotQueried, configured(AsrServiceId.Qwen, AsrServiceId.Doubao))
        )
        assertEquals(
            AsrResolution.Ready(AsrServiceId.Tencent, VoiceBackendKind.Tencent),
            resolveCurrentService(tencent, localReady, Allowed, systemNotQueried, configured(AsrServiceId.Tencent))
        )
    }
}
