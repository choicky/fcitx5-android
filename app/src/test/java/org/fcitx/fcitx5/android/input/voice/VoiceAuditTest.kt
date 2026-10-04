/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F1: the Voice audit layer must reconstruct control / data-flow decisions and must never record
 * speech or secrets. These tests exercise the pure mappers, the encoder and the file sink on the
 * JVM, and use distinctive fixtures (transcript text, keys, bearer tokens, signed URLs, self-hosted
 * url/id/name) to prove none of it can reach the audit artifact.
 */
class VoiceAuditTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ---- distinctive, deliberately hostile fixtures ----
    private val transcript = "你好机密TRANSCRIPTFINAL-zq7x"
    private val partial = "你机密PARTIAL-aa9"
    private val apiKey = "sk-SECRETapikey-DONOTLOG-9f3a7b2c1d4e5f60a1b2c3d4e5f60718"
    private val bearer = "Bearer TOKENBEARER-abcdef0123456789ABCDEF"
    private val signedUrl = "wss://asr.tencent.com/live?SecretId=AKIDSECRETLINKx9&signature=sigDEADBEEFabcdef0123456789"
    private val selfHostedUrl = "wss://user:pass@home.example.internal:6006/SECRETPATH?token=TOKselfhosted1234567890abcdef"
    private val selfHostedName = "我的服务器NAME-机密-zz9"
    private val selfHostedId = "zzinst9182"

    private val forbidden = listOf(transcript, partial, apiKey, bearer, signedUrl, selfHostedUrl, selfHostedName, selfHostedId)

    private fun assertClean(text: String) = forbidden.forEach {
        assertFalse("audit leaked: $it", text.contains(it))
    }

    // ---- start routing ----

    @Test
    fun startStepRoutesToClosedLabels() {
        assertEquals(
            VoiceStartStep.Start(VoiceBackendKind.System).toAuditStart(0L),
            StartEvent(0L, VoiceAuditRoute.Start)
        )
        assertEquals(
            listOf("route" to "start"),
            VoiceStartStep.Start(VoiceBackendKind.System).toAuditStart(0L).fields
        )
        assertEquals(
            VoiceStartStep.RequestRecordAudio.toAuditStart(0L).route, VoiceAuditRoute.NeedRecordAudio
        )
        assertEquals(
            VoiceStartStep.RequestSystemAuthorization.toAuditStart(0L).route, VoiceAuditRoute.NeedSystemAuth
        )
        val rec = VoiceStartStep.ApplyRecommendation(Recommendation.SelectLocal(LocalAsrModel.FunAsrNano))
            .toAuditStart(0L)
        assertEquals(
            listOf("route" to "recommend", "recommendation" to "selectLocal", "model" to "FunAsrNano"),
            rec.fields
        )
        val un = VoiceStartStep.Unavailable(
            AsrResolution.CurrentUnavailable(AsrServiceId.External, UnavailableReason.NoVoiceIme)
        ).toAuditStart(0L)
        assertEquals(listOf("route" to "unavailable", "reason" to "NoVoiceIme"), un.fields)
        // a no-service terminal state still records only a closed-set reason
        assertEquals(
            "NoService",
            VoiceStartStep.Unavailable(AsrResolution.NoService).toAuditStart(0L).fields.last().second
        )
    }

    @Test
    fun externalAndroidRoutesAsExternalWithoutSession() {
        // Model A: the External provider is a hand-off only — no routed/state/committed events
        val step = voiceStartStep(AsrResolution.ExternalAndroid, recordAudioGranted = true)
        val start = step.toAuditStart(0L)
        assertEquals(VoiceAuditRoute.External, start.route)
        assertEquals(listOf("route" to "external"), start.fields) // no dest, kind or language
    }

    // ---- backend destination descriptor (data-flow fact, no url/id/name/token) ----

    @Test
    fun backendDescriptorIsSafeLabelOnly() {
        assertEquals(VoiceAuditDest.OnDevice to "local:XAsrOffline", VoiceBackendKind.LocalAsr(LocalAsrModel.XAsrOffline).auditDescriptor())
        assertEquals(VoiceAuditDest.OnDevice to "system", VoiceBackendKind.System.auditDescriptor())
        assertEquals(VoiceAuditDest.OnDevice to "captureProbe", VoiceBackendKind.CaptureProbe.auditDescriptor())
        assertEquals(VoiceAuditDest.ManagedCloud to "doubao", VoiceBackendKind.Doubao.auditDescriptor())
        assertEquals(VoiceAuditDest.ManagedCloud to "doubaoDebug", VoiceBackendKind.DoubaoAsr.auditDescriptor())
        val hostile = SelfHostedInstance(selfHostedId, selfHostedName, SelfHostedProtocol.OpenAiCompatible, selfHostedUrl)
        val (dest, kind) = VoiceBackendKind.SelfHosted(hostile).auditDescriptor()
        assertEquals(VoiceAuditDest.SelfHosted, dest)
        assertEquals("selfhosted:openai-compatible", kind)
    }

    // ---- errors: class only, detail never recorded ----

    @Test
    fun errorDetailIsNeverRecorded() {
        val (svc, svcCode) = VoiceError.Service("$signedUrl text=$transcript").auditClass()
        assertEquals("service", svc)
        assertNull(svcCode)
        val (cap, capCode) = VoiceError.Capture("AudioRecord.read=-3 url=$selfHostedUrl").auditClass()
        assertEquals("capture", cap)
        assertNull(capCode)
        assertEquals(Pair("system", 5), VoiceError.System(5).auditClass())
        assertEquals(Pair("silent", null), VoiceError.Silent.auditClass())
        val line = VoiceAudit.encode(ErrorEvent(9L, svc, svcCode), 1L)
        assertClean(line)
        assertFalse(line.contains("-3")) // even a benign-looking capture detail is dropped
    }

    // ---- full realistic trace contains no content, in either build ----

    @Test
    fun realisticTraceLeaksNothing() {
        val lines = buildList {
            // Local committed session
            add(StartEvent(11L, VoiceAuditRoute.Start))
            add(RoutedEvent(11L, VoiceAuditDest.OnDevice, "local:XAsrOffline", external = false, fallbackArmed = false, language = "zh-CN"))
            add(StateEvent(11L, VoiceInputSession.State.Listening))
            add(CommittedEvent(11L))
            add(StateEvent(11L, VoiceInputSession.State.Idle))
            // Doubao external that fell back to Nano, then errored
            add(StartEvent(12L, VoiceAuditRoute.Start))
            add(RoutedEvent(12L, VoiceAuditDest.ManagedCloud, "doubao", external = true, fallbackArmed = true, language = "zh-CN"))
            add(FallbackEvent(12L, "FunAsrNano"))
            add(StateEvent(12L, VoiceInputSession.State.Stopping))
            val (cls, code) = VoiceError.Service(signedUrl).auditClass()
            add(ErrorEvent(12L, cls, code))
            // Self-hosted session: the hostile instance url/name/id must not reach the line
            add(StartEvent(13L, VoiceAuditRoute.Start))
            val hostile = SelfHostedInstance(selfHostedId, selfHostedName, SelfHostedProtocol.SherpaOnnx, selfHostedUrl)
            val (sd, sk) = VoiceBackendKind.SelfHosted(hostile).auditDescriptor()
            add(RoutedEvent(13L, sd, sk, external = true, fallbackArmed = false, language = "en-US"))
            // External Android hand-off only: start + handoff, never a routed/state/committed event
            add(StartEvent(0L, VoiceAuditRoute.External))
            add(HandoffEvent(0L, VoiceAuditHandoff.Switched, to = "com.samsung.android.voicespeech"))
        }.map { VoiceAudit.encode(it, 1_700_000_000_000L) }
        val joined = lines.joinToString("\n")
        assertClean(joined)
        assertTrue(lines.any { it.contains("routed dest=onDevice kind=local:XAsrOffline") })
        assertTrue(lines.any { it.contains("routed dest=managedCloud kind=doubao ext=true fb=true") })
        assertTrue(lines.any { it.contains("fallback to=FunAsrNano") })
        assertTrue(lines.any { it.contains("handoff outcome=switched to=com.samsung.android.voicespeech") })
        assertTrue(lines.any { it.contains("error class=service") })
        // the External sequence contributes exactly two lines and no session-bound event
        val externalIdx = lines.indexOfFirst { it.endsWith("start route=external") }
        assertTrue(externalIdx >= 0)
        assertTrue(lines[externalIdx + 1].contains("handoff"))
        assertEquals(externalIdx + 1, lines.lastIndex)
    }

    // ---- encoder is the last line of defense: strip structural chars, cap length ----

    @Test
    fun encoderStripsUnsafeAndCapsLength() {
        val hostile = "com.foo/../../etc?SecretId=$apiKey&b=$bearer abc\n你好" + "x".repeat(200)
        val line = VoiceAudit.encode(HandoffEvent(1L, VoiceAuditHandoff.Switched, to = hostile), 5L)
        val to = line.substringAfter("to=")
        assertFalse(to.contains(" "))
        assertFalse(to.contains("/"))
        assertFalse(to.contains("?"))
        assertFalse(to.contains("&"))
        assertFalse(to.contains("你"))
        assertFalse(to.contains("\n"))
        assertTrue("field must be capped to 64", to.length <= 64)
    }

    // ---- sink: bounded append, no-backup-style file, best-effort ----

    @Test
    fun recordAppendsAndCapsRetention() {
        val file = File(folder.root, "voice/session-audit")
        var clock = 0L
        val audit = VoiceAudit({ file }, { clock++ })
        repeat(VoiceAudit.MAX_RECORDS + 5) { audit.record(StateEvent(it.toLong(), VoiceInputSession.State.Listening)) }
        val lines = audit.read()
        assertEquals(VoiceAudit.MAX_RECORDS, lines.size)
        // the oldest five were dropped, the newest retained; ts and token advance together here
        assertTrue(lines.first().startsWith("5 "))
        assertFalse(lines.any { it.contains(" 4 state=") })
        assertTrue(lines.last().startsWith("${VoiceAudit.MAX_RECORDS + 4} "))
    }

    @Test
    fun recordIsBestEffortAndNeverThrows() {
        // a path whose parent cannot be made writable: file() itself throws
        val audit = VoiceAudit({ throw IllegalStateException("no context yet") })
        audit.record(CommittedEvent(1L)) // must not propagate
        assertTrue(audit.read().isEmpty())
    }

    @Test
    fun recordWritesThenReadsBackInOrder() {
        val file = File(folder.root, "voice/session-audit")
        val audit = VoiceAudit({ file }, { 42L })
        audit.record(StartEvent(7L, VoiceAuditRoute.Start))
        audit.record(RoutedEvent(7L, VoiceAuditDest.ManagedCloud, "qwen", true, false, "zh-CN"))
        val lines = audit.read()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("42 7 start route=start"))
        assertTrue(lines[1].startsWith("42 7 routed dest=managedCloud kind=qwen ext=true fb=false lang=zh-CN"))
    }

    private fun List<String>.codes() = map { it.split(' ').drop(2).joinToString(" ") }

    @Test
    fun cancelReconstructsTheDiscardedTermination() {
        val file = File(folder.root, "voice/session-audit")
        var clock = 100L
        val audit = VoiceAudit({ file }, { clock++ })
        audit.record(StateEvent(1L, VoiceInputSession.State.Listening))
        audit.record(CancelEvent(1L))
        audit.record(StateEvent(1L, VoiceInputSession.State.Idle))

        val codes = audit.read().codes()
        assertEquals(listOf("state state=Listening", "cancel", "state state=Idle"), codes)
        assertFalse(codes.any { it.contains("committed") || it == "stop" })
    }

    @Test
    fun stopThenFinalReconstructsTheCommittedTermination() {
        val file = File(folder.root, "voice/session-audit")
        var clock = 200L
        val audit = VoiceAudit({ file }, { clock++ })
        audit.record(StartEvent(0L, VoiceAuditRoute.Start))
        audit.record(StateEvent(1L, VoiceInputSession.State.Starting))
        audit.record(RoutedEvent(1L, VoiceAuditDest.OnDevice, "system", external = false, fallbackArmed = false, language = "zh-CN"))
        audit.record(StateEvent(1L, VoiceInputSession.State.Listening))
        audit.record(StopEvent(1L))
        audit.record(StateEvent(1L, VoiceInputSession.State.Stopping))
        audit.record(CommittedEvent(1L)) // Output.commit(text): the text itself is never referenced
        audit.record(StateEvent(1L, VoiceInputSession.State.Idle))

        assertEquals(
            listOf(
                "start route=start",
                "state state=Starting",
                "routed dest=onDevice kind=system ext=false fb=false lang=zh-CN",
                "state state=Listening",
                "stop",
                "state state=Stopping",
                "committed",
                "state state=Idle"
            ),
            audit.read().codes()
        )
    }

    @Test
    fun blankFinalTerminatesWithoutACommittedEvent() {
        // VoiceInputFlow sends a null/blank final to clearComposing, not commit; the component
        // records `committed` only inside Output.commit, so nothing may appear for it here
        val file = File(folder.root, "voice/session-audit")
        var clock = 300L
        val audit = VoiceAudit({ file }, { clock++ })
        audit.record(StateEvent(1L, VoiceInputSession.State.Listening))
        audit.record(StopEvent(1L))
        audit.record(StateEvent(1L, VoiceInputSession.State.Stopping))
        audit.record(StateEvent(1L, VoiceInputSession.State.Idle))
        assertFalse(audit.read().any { it.contains("committed") })
    }

    @Test
    fun fallbackTransitionKeepsTheAuditSessionId() {
        // D035: Output.fellBack (no reportError) after a pre-establishment Service failure; the
        // backend generation changes but the audit session id stays stable across the rebind
        val file = File(folder.root, "voice/session-audit")
        var clock = 400L
        val audit = VoiceAudit({ file }, { clock++ })
        audit.record(RoutedEvent(3L, VoiceAuditDest.ManagedCloud, "doubao", external = true, fallbackArmed = true, language = "zh-CN"))
        audit.record(StateEvent(3L, VoiceInputSession.State.Listening))
        audit.record(FallbackEvent(3L, LocalAsrModel.FunAsrNano.name))
        audit.record(StateEvent(3L, VoiceInputSession.State.Listening))
        audit.record(CommittedEvent(3L))
        audit.record(StateEvent(3L, VoiceInputSession.State.Idle))

        val lines = audit.read()
        assertEquals(1, lines.count { it.contains("fallback to=FunAsrNano") })
        assertFalse(lines.any { it.contains("error") })
        assertTrue(lines.all { it.split(' ')[1] == "3" })
    }

    @Test
    fun externalWithoutAVoiceImeRecordsTheExplicitDeadEnd() {
        // the saved provider stays External (D055): the audit shows route=external + handoff=noIme
        // and no in-IME session was ever bound
        val file = File(folder.root, "voice/session-audit")
        var clock = 500L
        val audit = VoiceAudit({ file }, { clock++ })
        audit.record(StartEvent(0L, VoiceAuditRoute.External))
        audit.record(HandoffEvent(0L, outcome = VoiceAuditHandoff.NoIme))

        val lines = audit.read()
        assertEquals(listOf("start route=external", "handoff outcome=noIme"), lines.codes())
        assertFalse(lines.any { it.contains("routed") || it.contains("state") || it.contains("committed") })
    }

    @Test
    fun staleSessionEventsStaySeparableByToken() {
        // session 1 is cancelled; a late event still carrying its token must not merge into the
        // reconstruction of session 2
        val file = File(folder.root, "voice/session-audit")
        var clock = 600L
        val audit = VoiceAudit({ file }, { clock++ })
        audit.record(StateEvent(1L, VoiceInputSession.State.Listening))
        audit.record(CancelEvent(1L))
        audit.record(StateEvent(1L, VoiceInputSession.State.Idle))
        audit.record(CommittedEvent(1L)) // late final of the cancelled session
        audit.record(StartEvent(0L, VoiceAuditRoute.Start))
        audit.record(StateEvent(2L, VoiceInputSession.State.Listening))
        audit.record(CommittedEvent(2L))
        audit.record(StateEvent(2L, VoiceInputSession.State.Idle))

        val session2 = audit.read().filter { it.split(' ')[1] == "2" }
        assertEquals(listOf("state state=Listening", "committed", "state state=Idle"), session2.codes())
        val session1 = audit.read().filter { it.split(' ')[1] == "1" }
        assertEquals(1, session1.count { it.contains("committed") })
    }
}
