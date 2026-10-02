/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ModelPauseTest {
    private val executor = Executors.newFixedThreadPool(2)
    private val tasks = ModelTasks(CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())) {}
    private val model = LocalAsrModel.FunAsrNano
    private val root = Files.createTempDirectory("model-pause").toFile()
    private val installer = LocalModelInstaller(root)
    private val requests = CopyOnWriteArrayList<Pair<String, Long>>()
    private val contents = model.requiredFiles.associateWith { "content of $it".toByteArray() }
    private val entry = ModelCatalogEntry(model, "test", contents.map { (path, bytes) ->
        ModelFile(path, bytes.size.toLong(), MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) })
    }, null)

    @After
    fun stop() { executor.shutdownNow() }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            Thread.sleep(5)
        }
    }

    /** A partial read whose abort takes time: ownership must outlive the stop request. */
    private class HeldStream(private val bytes: ByteArray, private val holdAtEnd: Boolean = false) : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val aborts = AtomicInteger()
        private var copied = false
        override fun read(): Int = error("buffer read expected")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!copied) {
                copied = true
                val count = if (holdAtEnd) bytes.size else 5
                bytes.copyInto(buffer, offset, 0, count)
                return count
            }
            entered.countDown()
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { }
            }
            if (holdAtEnd) return -1
            throw IOException("transfer stopped")
        }
    }

    private fun start(held: HeldStream, path: String = entry.files[1].path): Boolean {
        val first = AtomicBoolean(true)
        return tasks.start(model, entry.totalBytes, allowPause = true,
            discardStaging = { installer.discardStaging(model) }) { handle ->
            val source: ModelFileSource = { file, offset ->
                requests += file.path to offset
                if (file.path == path && first.getAndSet(false)) {
                    handle.onCancel { held.aborts.incrementAndGet() }
                    ModelStream(held)
                } else {
                    val bytes = contents.getValue(file.path)
                    ModelStream(ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()), offset)
                }
            }
            installer.install(entry, source, handle::progress, { handle.cancelled },
                paused = { handle.paused }, downloadPhase = handle::downloadPhase)
        }
    }

    private fun pause(held: HeldStream) {
        assertTrue(held.entered.await(5, TimeUnit.SECONDS))
        tasks.pause(model)
        assertEquals(ModelTasks.State.Pausing, tasks.state(model))
    }

    @Test
    fun pausePreservesCompletedAndPartialFilesThenResumeVerifiesAndInstalls() {
        val held = HeldStream(contents.getValue(entry.files[1].path))
        assertTrue(start(held))
        pause(held)
        tasks.pause(model)
        assertEquals(1, held.aborts.get())
        assertTrue(tasks.isBusy(model))
        assertFalse(tasks.resume(model))
        assertFalse(tasks.start(model, 1) {})
        held.release.countDown()
        await("stable paused") { !tasks.isBusy(model) }
        assertEquals(ModelTasks.State.Paused, tasks.state(model))
        assertEquals(entry.files[0].size + 5, installer.stagedBytes(model))
        assertFalse(installer.isInstalled(model))

        assertTrue(tasks.resume(model))
        assertFalse(tasks.resume(model))
        await("installed") { !tasks.isBusy(model) }
        assertEquals(ModelTasks.State.Finished, tasks.state(model))
        assertTrue(installer.isInstalled(model))
        assertEquals(listOf(0L), requests.filter { it.first == entry.files[0].path }.map { it.second })
        assertEquals(listOf(0L, 5L), requests.filter { it.first == entry.files[1].path }.map { it.second })
        entry.files.forEach { assertEquals(it.sha256, LocalModelInstaller.sha256(installer.modelDir(model).resolve(it.path))) }
        assertEquals(0L, installer.stagedBytes(model))
    }

    @Test
    fun concurrentResumeStartsOneWorkerAndOldHandleCannotChangeIt() {
        val runs = AtomicInteger()
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        lateinit var oldHandle: ModelTasks.Handle
        tasks.start(model, 1, allowPause = true) { h ->
            h.downloadPhase(true)
            if (runs.incrementAndGet() == 1) {
                oldHandle = h
                h.onCancel { stopped.countDown() }
                entered.countDown()
                stopped.await()
                h.downloadPhase(false) // observes Pause before leaving the worker
            } else {
                resumed.countDown()
                finish.await()
                h.downloadPhase(false)
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        tasks.pause(model)
        await("paused") { !tasks.isBusy(model) }
        val one = executor.submit<Boolean> { tasks.resume(model) }
        val two = executor.submit<Boolean> { tasks.resume(model) }
        assertEquals(1, listOf(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)).count { it })
        assertTrue(resumed.await(5, TimeUnit.SECONDS))
        oldHandle.downloadPhase(false)
        oldHandle.progress(999, 999)
        assertEquals(ModelTasks.State.Running(0, 1, canPause = true), tasks.state(model))
        assertEquals(2, runs.get())
        finish.countDown()
        await("finished") { !tasks.isBusy(model) }
    }

    @Test
    fun runningCancelStillDiscardsPartialDownloadAndRejectsPause() {
        val held = HeldStream(contents.getValue(entry.files[1].path))
        assertTrue(start(held))
        assertTrue(held.entered.await(5, TimeUnit.SECONDS))
        tasks.cancel(model)
        tasks.pause(model)
        assertEquals(ModelTasks.State.Cancelling, tasks.state(model))
        held.release.countDown()
        await("cancelled") { !tasks.isBusy(model) }
        assertEquals(0L, installer.stagedBytes(model))
        assertFalse(tasks.resume(model))
    }

    @Test
    fun pausedCancelDiscardsOnlyStagingAndClearsResume() {
        installer.install(entry, { file -> ByteArrayInputStream(contents.getValue(file.path)) })
        val held = HeldStream(contents.getValue(entry.files[1].path))
        assertTrue(start(held))
        pause(held)
        held.release.countDown()
        await("paused") { !tasks.isBusy(model) }
        tasks.cancel(model)
        tasks.cancel(model)
        assertEquals(0L, installer.stagedBytes(model))
        assertTrue(installer.isInstalled(model))
        assertFalse(tasks.resume(model))
        assertTrue((tasks.state(model) as ModelTasks.State.Failed).reason is InstallFailure.Cancelled)
    }

    @Test
    fun cancelWinsOverPauseWhileWorkerStillOwnsStaging() {
        val held = HeldStream(contents.getValue(entry.files[1].path))
        assertTrue(start(held))
        pause(held)
        tasks.cancel(model)
        tasks.pause(model)
        assertEquals(ModelTasks.State.Cancelling, tasks.state(model))
        assertTrue(tasks.isBusy(model))
        held.release.countDown()
        await("cancelled") { !tasks.isBusy(model) }
        assertEquals(0L, installer.stagedBytes(model))
        assertFalse(tasks.resume(model))
        assertTrue((tasks.state(model) as ModelTasks.State.Failed).reason is InstallFailure.Cancelled)
    }

    @Test
    fun pauseAtEndOfLastCopyPreventsSwapAndResumeReusesVerifiedFiles() {
        val path = entry.files.last().path
        val held = HeldStream(contents.getValue(path), holdAtEnd = true)
        assertTrue(start(held, path))
        pause(held)
        held.release.countDown()
        await("paused before swap") { !tasks.isBusy(model) }
        assertEquals(ModelTasks.State.Paused, tasks.state(model))
        assertEquals(entry.totalBytes, installer.stagedBytes(model))
        assertFalse(installer.isInstalled(model))
        assertTrue(tasks.resume(model))
        await("verified reuse") { !tasks.isBusy(model) }
        assertEquals(ModelTasks.State.Finished, tasks.state(model))
        assertTrue(installer.isInstalled(model))
        tasks.pause(model)
        assertEquals(ModelTasks.State.Finished, tasks.state(model))
    }

    @Test
    fun pauseAfterVerificationGateClosesCannotChangeSuccessfulCompletion() {
        val gateClosed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        tasks.start(model, 1, allowPause = true) { h ->
            h.downloadPhase(true)
            h.downloadPhase(false)
            gateClosed.countDown()
            finish.await()
        }
        assertTrue(gateClosed.await(5, TimeUnit.SECONDS))
        tasks.pause(model)
        assertTrue(tasks.state(model) is ModelTasks.State.Running)
        assertFalse((tasks.state(model) as ModelTasks.State.Running).canPause)
        finish.countDown()
        await("finished") { !tasks.isBusy(model) }
        assertEquals(ModelTasks.State.Finished, tasks.state(model))
    }

    @Test
    fun anImportOrFailureDoesNotBecomePaused() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        tasks.start(model, 1) { h ->
            h.downloadPhase(true)
            entered.countDown()
            finish.await()
            throw IOException("network failure")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        tasks.pause(model)
        assertTrue(tasks.state(model) is ModelTasks.State.Running)
        finish.countDown()
        await("failed") { !tasks.isBusy(model) }
        assertTrue(tasks.state(model) is ModelTasks.State.Failed)
        assertFalse(tasks.resume(model))
    }

    @Test
    fun returningNormallyEvenAfterPauseCannotMarkCompletedWorkPaused() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        tasks.start(model, 1, allowPause = true) { h ->
            h.downloadPhase(true)
            entered.countDown()
            finish.await()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        tasks.pause(model)
        finish.countDown()
        await("completed work") { !tasks.isBusy(model) }
        assertEquals(ModelTasks.State.Finished, tasks.state(model))
    }
}
