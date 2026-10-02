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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRowsTest {

    @Test
    fun pauseActionsFollowActualWorkerPhaseAndOwnership() {
        assertEquals(listOf(ModelAction.Pause, Cancel, Details),
            row(task = ModelTasks.State.Running(1, 2, canPause = true), busy = true).actions)
        assertEquals(listOf(Cancel, Details),
            row(task = ModelTasks.State.Running(1, 2, canPause = false), busy = true).actions)
        assertEquals(ModelRow(ModelStatus.Pausing, listOf(Cancel, Details)),
            row(task = ModelTasks.State.Pausing, busy = true))
        // New UI projections (e.g. a recreated page) read the same stable process-local state.
        repeat(2) {
            assertEquals(ModelRow(ModelStatus.Paused, listOf(ModelAction.Resume, Cancel, Details)),
                row(task = ModelTasks.State.Paused, staged = 5))
        }
        assertEquals(ModelStatus.Partial, row(task = null, staged = 5).status)
    }

    private fun row(
        installed: Boolean = false,
        task: ModelTasks.State? = null,
        busy: Boolean = false,
        staged: Long = 0,
        offered: Boolean = true,
        enabled: Boolean = false,
        current: Boolean = false
    ) = modelRow(installed, task, busy, { check(!busy) { "staged bytes read while busy" }; staged }, offered, enabled, current)

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
    fun installedEnabledAndInUseAreDistinct() {
        // installing selects nothing
        assertEquals(ModelRow(ModelStatus.Installed, listOf(Use, Remove, Details)), row(installed = true))
        assertEquals(ModelRow(ModelStatus.Enabled, listOf(Use, Remove, Details)), row(installed = true, enabled = true))
        assertEquals(ModelRow(ModelStatus.InUse, listOf(Remove, Details)), row(installed = true, enabled = true, current = true))
        // selected but disabled: not in use, Use is offered again
        assertEquals(ModelStatus.Installed, row(installed = true, current = true).status)
    }

    @Test
    fun anUninstalledModelIsNeverShownAsUsable() {
        // enabled or current from an earlier install, now removed, partial or failed
        for (staged in listOf(0L, 5L)) {
            val r = row(staged = staged, enabled = true, current = true)
            assertTrue(r.status in setOf(ModelStatus.NotInstalled, ModelStatus.Partial))
            assertFalse(Use in r.actions)
        }
        val failed = row(task = ModelTasks.State.Failed(InstallFailure.Mismatch("x"), "x"), enabled = true)
        assertEquals(ModelStatus.Failed, failed.status)
        assertFalse(Use in failed.actions)
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
        listOf(row(), row(offered = false), row(busy = true), row(installed = true), row(installed = true, enabled = true, current = true), row(staged = 1))
            .forEach { assert(it.actions.any { a -> a != Details }) { it.toString() } }
    }
}
