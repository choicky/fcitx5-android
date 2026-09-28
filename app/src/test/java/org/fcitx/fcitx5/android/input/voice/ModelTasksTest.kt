/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
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
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelTasksTest {

    private val executor = Executors.newFixedThreadPool(4)
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val changes = AtomicInteger()
    private val tasks = ModelTasks(scope) { changes.incrementAndGet() }

    private val model = LocalAsrModel.ZipformerZh
    private val root = Files.createTempDirectory("models").toFile()
    private val installer = LocalModelInstaller(root)
    private val contents = model.requiredFiles.associateWith { "content of $it".toByteArray() }
    private val entry = ModelCatalogEntry(
        model, "test", contents.map { (path, bytes) ->
            ModelFile(path, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }, null
    )

    @After
    fun stop() = executor.shutdownNow().let { }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    /** A read that blocks like a stalled HTTP body and, like one, ignores interruption. */
    private class StalledRead(private val bytes: ByteArray) : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private var i = 0
        override fun read(): Int {
            if (i == 2) {
                entered.countDown()
                while (true) {
                    try {
                        release.await()
                        break
                    } catch (_: InterruptedException) {
                    }
                }
            }
            return if (i < bytes.size) bytes[i++].toInt() and 0xff else -1
        }
    }

    @Test
    fun aCancelledWorkerKeepsTheModelUntilItExitsAndTheRetryDoesNotOverlap() {
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        val stalled = StalledRead(contents["encoder.int8.onnx"]!!)
        var lateProgress: (() -> Unit)? = null

        fun work(source: ModelFileSource): (ModelTasks.Handle) -> Unit = { handle ->
            maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            try {
                lateProgress = { handle.progress(1, 1) }
                installer.install(entry, source, handle::progress, { handle.cancelled })
            } finally {
                inside.decrementAndGet()
            }
        }

        val first: ModelFileSource = { f, _ ->
            ModelStream(if (f.path == "encoder.int8.onnx") stalled else ByteArrayInputStream(contents[f.path]!!))
        }
        assertTrue(tasks.start(model, entry.totalBytes, work(first)))
        assertTrue(stalled.entered.await(5, TimeUnit.SECONDS))

        tasks.cancel(model)
        assertEquals(ModelTasks.State.Cancelling, tasks.state(model))
        // the worker is still inside its read: the model stays occupied
        assertTrue(tasks.isBusy(model))
        val retry: ModelFileSource = { f, _ -> ModelStream(ByteArrayInputStream(contents[f.path]!!)) }
        assertFalse(tasks.start(model, entry.totalBytes, work(retry)))
        // progress the old worker reports after the cancel changes nothing
        lateProgress!!()
        assertEquals(ModelTasks.State.Cancelling, tasks.state(model))

        stalled.release.countDown()
        waitUntil("the cancelled worker to exit") { !tasks.isBusy(model) }
        val ended = tasks.state(model)
        assertTrue(ended is ModelTasks.State.Failed && ended.reason is InstallFailure.Cancelled)
        assertEquals(0L, installer.stagedBytes(model))

        assertTrue(tasks.start(model, entry.totalBytes, work(retry)))
        waitUntil("the retry to finish") { !tasks.isBusy(model) }
        assertEquals(ModelTasks.State.Finished, tasks.state(model))
        assertTrue(installer.isInstalled(model))
        assertEquals(1, maxInside.get())
    }

    @Test
    fun cancellingBeforeTheWorkerStartsFreesTheModel() {
        // occupy every thread, so the task cannot start yet
        val hold = CountDownLatch(1)
        repeat(4) { executor.execute { hold.await() } }
        var ran = false
        assertTrue(tasks.start(model, 1) { ran = true })
        tasks.cancel(model)
        assertTrue(tasks.isBusy(model))
        hold.countDown()
        waitUntil("the cancelled task to complete") { !tasks.isBusy(model) }
        assertFalse(ran)
        val ended = tasks.state(model)
        assertTrue(ended is ModelTasks.State.Failed && ended.reason is InstallFailure.Cancelled)
        assertTrue(tasks.start(model, 1) { })
        waitUntil("the next task to finish") { !tasks.isBusy(model) }
        assertEquals(ModelTasks.State.Finished, tasks.state(model))
    }

    @Test
    fun cancelRunsTheRegisteredAbortsOnce() {
        val aborted = AtomicInteger()
        val registered = CountDownLatch(1)
        val done = CountDownLatch(1)
        lateinit var handle: ModelTasks.Handle
        tasks.start(model, 1) { h ->
            handle = h
            h.onCancel { aborted.incrementAndGet() }
            registered.countDown()
            done.await()
        }
        assertTrue(registered.await(5, TimeUnit.SECONDS))
        tasks.cancel(model)
        tasks.cancel(model)
        assertEquals(1, aborted.get())
        // registered after the cancel: runs right away
        handle.onCancel { aborted.incrementAndGet() }
        assertEquals(2, aborted.get())
        done.countDown()
        waitUntil("the task to exit") { !tasks.isBusy(model) }
    }

    @Test
    fun failuresAreReportedRedacted() {
        tasks.start(model, 1) { throw java.io.IOException("GET https://h/m?token=abc failed") }
        waitUntil("the task to fail") { !tasks.isBusy(model) }
        val ended = tasks.state(model) as ModelTasks.State.Failed
        assertEquals("GET https://h/m?*** failed", ended.detail)
        waitUntil("start and end to be reported") { changes.get() >= 2 }
    }
}
