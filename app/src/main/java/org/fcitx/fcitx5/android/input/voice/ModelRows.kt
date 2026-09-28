/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

/** What a Model Manager row offers when tapped, in display order. */
internal enum class ModelAction { Download, DownloadFrom, Import, Discard, Cancel, Use, Remove, Details }

/**
 * The primary status a Model Manager row shows. Installed, enabled and in use are distinct:
 * only an enabled installed model can be chosen as the current service, and only the current
 * one runs.
 */
internal enum class ModelStatus { NotInstalled, Partial, Running, Stopping, Failed, Installed, Enabled, InUse }

internal data class ModelRow(val status: ModelStatus, val actions: List<ModelAction>)

/**
 * The row for one catalog model: its status and exactly the actions that apply.
 * [downloadOffered] carries the licence gate (A only in test builds, D037); [enabled] and
 * [current] are the model's own service in the selection. [stagedBytes] is only read when no
 * task runs.
 */
internal fun modelRow(
    installed: Boolean,
    task: ModelTasks.State?,
    busy: Boolean,
    stagedBytes: () -> Long,
    downloadOffered: Boolean,
    enabled: Boolean,
    current: Boolean
): ModelRow {
    if (busy) {
        return if (task == ModelTasks.State.Cancelling) ModelRow(ModelStatus.Stopping, listOf(ModelAction.Details))
        else ModelRow(ModelStatus.Running, listOf(ModelAction.Cancel, ModelAction.Details))
    }
    if (installed) {
        val inUse = enabled && current
        val actions = listOfNotNull(ModelAction.Use.takeUnless { inUse }, ModelAction.Remove, ModelAction.Details)
        val status = when {
            inUse -> ModelStatus.InUse
            enabled -> ModelStatus.Enabled
            else -> ModelStatus.Installed
        }
        return ModelRow(status, actions)
    }
    val staged = stagedBytes()
    val actions = buildList {
        if (downloadOffered) {
            add(ModelAction.Download)
            add(ModelAction.DownloadFrom)
        }
        add(ModelAction.Import)
        if (staged > 0) add(ModelAction.Discard)
        add(ModelAction.Details)
    }
    val status = when {
        task is ModelTasks.State.Failed && task.reason !is InstallFailure.Cancelled -> ModelStatus.Failed
        staged > 0 -> ModelStatus.Partial
        else -> ModelStatus.NotInstalled
    }
    return ModelRow(status, actions)
}
