/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.input.voice.ModelAction.Cancel
import org.fcitx.fcitx5.android.input.voice.ModelAction.Details
import org.fcitx.fcitx5.android.input.voice.ModelAction.Discard
import org.fcitx.fcitx5.android.input.voice.ModelAction.Download
import org.fcitx.fcitx5.android.input.voice.ModelAction.DownloadFrom
import org.fcitx.fcitx5.android.input.voice.ModelAction.Import
import org.fcitx.fcitx5.android.input.voice.ModelAction.Remove
import org.fcitx.fcitx5.android.input.voice.ModelAction.Use
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRowsTest {

    private fun row(
        installed: Boolean = false,
        task: ModelTasks.State? = null,
        busy: Boolean = false,
        staged: Long = 0,
        offered: Boolean = true,
        selected: Boolean = false
    ) = modelRow(installed, task, busy, staged, offered, selected)

    @Test
    fun notInstalledInATestBuildOffersDownloadsAndImport() {
        assertEquals(ModelRow(ModelStatus.NotInstalled, listOf(Download, DownloadFrom, Import, Details)), row())
    }

    @Test
    fun withoutAnOfferedDownloadOnlyImportRemains() {
        // A in a release build: the licence gate removes both download actions
        assertEquals(listOf(Import, Details), row(offered = false).actions)
    }

    @Test
    fun aRunningTaskOffersCancelOnly() {
        assertEquals(ModelRow(ModelStatus.Running, listOf(Cancel, Details)), row(task = ModelTasks.State.Running(1, 2), busy = true))
        assertEquals(ModelRow(ModelStatus.Stopping, listOf(Details)), row(task = ModelTasks.State.Cancelling, busy = true))
    }

    @Test
    fun installedOffersUseAndRemove() {
        assertEquals(ModelRow(ModelStatus.Installed, listOf(Use, Remove, Details)), row(installed = true))
        assertEquals(ModelRow(ModelStatus.InUse, listOf(Remove, Details)), row(installed = true, selected = true))
    }

    @Test
    fun aPartialOrFailedDownloadCanBeResumedOrDiscarded() {
        assertEquals(ModelRow(ModelStatus.Partial, listOf(Download, DownloadFrom, Import, Discard, Details)), row(staged = 5))
        val failed = ModelTasks.State.Failed(InstallFailure.Mismatch("tokens.txt"), "mismatch")
        assertEquals(ModelStatus.Failed, row(task = failed).status)
        // a cancellation is not shown as a failure
        assertEquals(ModelStatus.NotInstalled, row(task = ModelTasks.State.Failed(InstallFailure.Cancelled(), "cancelled")).status)
    }

    @Test
    fun everyRowHasAtLeastOneActionBesidesDetails() {
        listOf(row(), row(offered = false), row(busy = true), row(installed = true), row(installed = true, selected = true), row(staged = 1))
            .forEach { assert(it.actions.any { a -> a != Details }) { it.toString() } }
    }
}
