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
        data class Running(val done: Long, val total: Long) : State

        /** Cancelled, but the worker has not exited yet; the model cannot be restarted. */
        data object Cancelling : State
        data class Failed(val reason: InstallFailure?, val detail: String) : State
        data object Finished : State
    }

    /** What a worker sees of its own task. */
    interface Handle {
        val cancelled: Boolean
        fun progress(done: Long, total: Long)

        /**
         * [action] runs once when the task is cancelled (right away if it already is), for
         * example to cancel an HTTP call whose blocking read would otherwise go on.
         */
        fun onCancel(action: () -> Unit)
    }

    private inner class Task(val model: LocalAsrModel) : Handle {
        // guarded by the ModelTasks lock
        var job: Job? = null
        val aborts = mutableListOf<() -> Unit>()

        @Volatile
        override var cancelled = false

        override fun progress(done: Long, total: Long) = update(this, State.Running(done, total))

        override fun onCancel(action: () -> Unit) {
            val now = synchronized(this@ModelTasks) {
                if (!cancelled) aborts += action
                cancelled
            }
            if (now) runCatching(action)
        }
    }

    private val owners = mutableMapOf<LocalAsrModel, Task>()
    private val states = mutableMapOf<LocalAsrModel, State>()

    @Synchronized
    fun state(model: LocalAsrModel): State? = states[model]

    /** True from [start] until the worker has exited, including while it is cancelling. */
    @Synchronized
    fun isBusy(model: LocalAsrModel) = model in owners

    /**
     * Starts [work] for [model] unless the model is still occupied; returns whether it started.
     * [work] returns normally on success and throws on failure or cancellation.
     */
    fun start(model: LocalAsrModel, total: Long, work: (Handle) -> Unit): Boolean {
        val task = Task(model)
        synchronized(this) {
            if (model in owners) return false
            owners[model] = task
            states[model] = State.Running(0, total)
        }
        onChange(model)
        val job = scope.launch {
            val result = runCatching { work(task) }
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

    fun cancel(model: LocalAsrModel) {
        val (job, aborts) = synchronized(this) {
            val task = owners[model] ?: return
            if (task.cancelled) return
            task.cancelled = true
            states[model] = State.Cancelling
            (task.job to task.aborts.toList()).also { task.aborts.clear() }
        }
        onChange(model)
        aborts.forEach { runCatching(it) }
        job?.cancel()
    }

    private fun update(task: Task, state: State) {
        val notify = synchronized(this) {
            // only the owner, and a cancelled task no longer reports progress
            if (owners[task.model] !== task || task.cancelled) return
            val previous = states[task.model]
            states[task.model] = state
            // progress is reported at most once per percent
            !(previous is State.Running && state is State.Running &&
                    state.done * 100 / state.total.coerceAtLeast(1) ==
                    previous.done * 100 / previous.total.coerceAtLeast(1))
        }
        if (notify) onChange(task.model)
    }

    private fun finish(task: Task, failure: Throwable?) {
        synchronized(this) {
            // the first of the body's end and the job's completion releases the model
            if (owners[task.model] !== task) return
            owners -= task.model
            states[task.model] = when {
                task.cancelled -> State.Failed(InstallFailure.Cancelled(), "cancelled")
                failure == null -> State.Finished
                else -> State.Failed(failure as? InstallFailure, ErrorRedaction.redact(failure.message.orEmpty()))
            }
        }
        onChange(task.model)
    }
}
