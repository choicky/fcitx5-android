/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

/** What a Model Manager row offers when tapped, in display order. */
internal enum class ModelAction { Download, DownloadFrom, Import, Discard, Cancel, Use, Remove, Details }

/** The primary status a Model Manager row shows. */
internal enum class ModelStatus { NotInstalled, Partial, Running, Stopping, Failed, Installed, InUse }

internal data class ModelRow(val status: ModelStatus, val actions: List<ModelAction>)

/**
 * The row for one catalog model: its status and exactly the actions that apply.
 * [downloadOffered] carries the licence gate (A only in test builds, D037); [selected] means
 * it is already the configured on-device model.
 */
internal fun modelRow(
    installed: Boolean,
    task: ModelTasks.State?,
    busy: Boolean,
    stagedBytes: Long,
    downloadOffered: Boolean,
    selected: Boolean
): ModelRow {
    if (busy) {
        return if (task == ModelTasks.State.Cancelling) ModelRow(ModelStatus.Stopping, listOf(ModelAction.Details))
        else ModelRow(ModelStatus.Running, listOf(ModelAction.Cancel, ModelAction.Details))
    }
    if (installed) {
        val actions = listOfNotNull(ModelAction.Use.takeUnless { selected }, ModelAction.Remove, ModelAction.Details)
        return ModelRow(if (selected) ModelStatus.InUse else ModelStatus.Installed, actions)
    }
    val actions = buildList {
        if (downloadOffered) {
            add(ModelAction.Download)
            add(ModelAction.DownloadFrom)
        }
        add(ModelAction.Import)
        if (stagedBytes > 0) add(ModelAction.Discard)
        add(ModelAction.Details)
    }
    val status = when {
        task is ModelTasks.State.Failed && task.reason !is InstallFailure.Cancelled -> ModelStatus.Failed
        stagedBytes > 0 -> ModelStatus.Partial
        else -> ModelStatus.NotInstalled
    }
    return ModelRow(status, actions)
}
