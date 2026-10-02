/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One install task per model. A model stays occupied from [start] until its worker has really
 * exited: cancelling a coroutine does not stop a blocking read, and a new task must not touch
 * the staging directory while the old worker may still write to it. Only the task that owns
 * a model changes that model's state, so a cancelled worker's late progress or result cannot
 * overwrite a newer task's state.
 */
internal class ModelTasks(
    private val scope: CoroutineScope,
    /** Called after every state change that is worth showing; not under the lock. */
    private val onChange: (LocalAsrModel) -> Unit
) {

    sealed interface State {
        data class Running(val done: Long, val total: Long, val canPause: Boolean = false) : State

        data object Pausing : State
        data object Paused : State

        /** Cancelled, but the worker has not exited yet; the model cannot be restarted. */
        data object Cancelling : State
        data class Failed(val reason: InstallFailure?, val detail: String) : State
        data object Finished : State
    }

    /** What a worker sees of its own task. */
    interface Handle {
        val cancelled: Boolean
        val paused: Boolean
        fun progress(done: Long, total: Long)

        /** Closes the pause gate before hashing/installing, or opens it for network/copy. */
        fun downloadPhase(active: Boolean)

        /**
         * [action] runs when the worker is stopped by Pause or Cancel (immediately if already
         * stopped), for example to abort an HTTP call whose blocking read would otherwise go on.
         */
        fun onCancel(action: () -> Unit)
    }

    private enum class StopReason { Pause, Cancel }

    private inner class Task(
        val model: LocalAsrModel,
        val total: Long,
        val allowPause: Boolean,
        val discardStaging: () -> Unit,
        val work: (Handle) -> Unit
    ) : Handle {
        // guarded by the ModelTasks lock
        var job: Job? = null
        val aborts = mutableListOf<() -> Unit>()

        @Volatile
        var stopReason: StopReason? = null

        override val cancelled get() = stopReason == StopReason.Cancel
        override val paused get() = stopReason == StopReason.Pause

        @Volatile
        var canPause = false

        override fun progress(done: Long, total: Long) = update(this, State.Running(done, total, canPause))

        override fun downloadPhase(active: Boolean) {
            synchronized(this@ModelTasks) {
                if (owners[model] !== this) return
                if (cancelled) throw InstallFailure.Cancelled()
                if (paused) throw InstallFailure.Paused()
                canPause = active && allowPause
                val running = states[model] as? State.Running ?: return
                states[model] = running.copy(canPause = canPause)
            }
            onChange(model)
        }

        override fun onCancel(action: () -> Unit) {
            val now = synchronized(this@ModelTasks) {
                if (stopReason == null) aborts += action
                stopReason != null
            }
            if (now) runCatching(action)
        }
    }

    private val owners = mutableMapOf<LocalAsrModel, Task>()
    private val states = mutableMapOf<LocalAsrModel, State>()
    // Stable Paused retains its source in memory, but no worker owns its staging directory.
    private val pausedTasks = mutableMapOf<LocalAsrModel, Task>()

    @Synchronized
    fun state(model: LocalAsrModel): State? = states[model]

    /** True from [start] until the worker has exited, including while it is cancelling. */
    @Synchronized
    fun isBusy(model: LocalAsrModel) = model in owners

    /**
     * Starts [work] for [model] unless the model is still occupied; returns whether it started.
     * [work] returns normally on success and throws on failure or cancellation.
     */
    fun start(model: LocalAsrModel, total: Long, work: (Handle) -> Unit): Boolean =
        start(model, total, allowPause = false, work = work)

    fun start(
        model: LocalAsrModel,
        total: Long,
        allowPause: Boolean,
        discardStaging: () -> Unit = {},
        work: (Handle) -> Unit
    ): Boolean {
        val task = Task(model, total, allowPause, discardStaging, work)
        return startTask(task)
    }

    private fun startTask(task: Task, pausedOwner: Task? = null): Boolean {
        val model = task.model
        synchronized(this) {
            if (model in owners) return false
            if (pausedOwner != null && pausedTasks[model] !== pausedOwner) return false
            owners[model] = task
            pausedTasks.remove(model)
            states[model] = State.Running(0, task.total)
        }
        onChange(model)
        val job = scope.launch {
            val result = runCatching { task.work(task) }
            finish(task, result.exceptionOrNull())
        }
        // runs after the body has returned, or instead of it when the job was cancelled
        // before it started; either way the worker is gone and the model is free again
        job.invokeOnCompletion { cause -> if (cause != null) finish(task, cause) }
        val cancelNow = synchronized(this) {
            task.job = job
            task.cancelled
        }
        if (cancelNow) job.cancel()
        return true
    }

    fun pause(model: LocalAsrModel) {
        val aborts = synchronized(this) {
            val task = owners[model] ?: return
            if (!task.canPause || task.stopReason != null) return
            task.stopReason = StopReason.Pause
            states[model] = State.Pausing
            task.aborts.toList().also { task.aborts.clear() }
        }
        onChange(model)
        aborts.forEach { runCatching(it) }
        // Do not cancel the coroutine: the installer must observe Pause and retain staging.
    }

    fun resume(model: LocalAsrModel): Boolean {
        val task = synchronized(this) { pausedTasks[model] } ?: return false
        return startTask(Task(model, task.total, task.allowPause, task.discardStaging, task.work), task)
    }

    fun cancel(model: LocalAsrModel) {
        val (job, aborts) = synchronized(this) {
            val task = owners[model]
            if (task == null) {
                val paused = pausedTasks[model] ?: return
                // Exclude Resume until cleanup finishes; never remove an installed copy here.
                val failure = runCatching(paused.discardStaging).exceptionOrNull()
                pausedTasks.remove(model)
                states[model] = cancellationResult(failure)
                null to emptyList<() -> Unit>()
            } else {
                if (task.cancelled) return
                task.stopReason = StopReason.Cancel // Cancel wins over an outstanding Pause.
                states[model] = State.Cancelling
                (task.job to task.aborts.toList()).also { task.aborts.clear() }
            }
        }
        onChange(model)
        aborts.forEach { runCatching(it) }
        job?.cancel()
    }

    private fun update(task: Task, state: State) {
        val notify = synchronized(this) {
            // only the owner, and a cancelled task no longer reports progress
            if (owners[task.model] !== task || task.stopReason != null) return
            val previous = states[task.model]
            states[task.model] = state
            // progress is reported at most once per percent
            !(previous is State.Running && state is State.Running &&
                    previous.canPause == state.canPause &&
                    state.done * 100 / state.total.coerceAtLeast(1) ==
                    previous.done * 100 / previous.total.coerceAtLeast(1))
        }
        if (notify) onChange(task.model)
    }

    private fun finish(task: Task, failure: Throwable?) {
        synchronized(this) {
            // the first of the body's end and the job's completion releases the model
            if (owners[task.model] !== task) return
            states[task.model] = when {
                task.cancelled -> cancellationResult(runCatching(task.discardStaging).exceptionOrNull())
                failure == null -> State.Finished
                task.paused && failure is InstallFailure.Paused -> {
                    pausedTasks[task.model] = task
                    State.Paused
                }
                else -> State.Failed(failure as? InstallFailure, ErrorRedaction.redact(failure.message.orEmpty()))
            }
            owners -= task.model
        }
        onChange(task.model)
    }

    private fun cancellationResult(failure: Throwable?): State.Failed =
        if (failure == null) State.Failed(InstallFailure.Cancelled(), "cancelled")
        else State.Failed(failure as? InstallFailure, ErrorRedaction.redact(failure.message.orEmpty()))
}
