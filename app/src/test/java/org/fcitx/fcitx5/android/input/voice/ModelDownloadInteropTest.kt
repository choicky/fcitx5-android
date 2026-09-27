/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Installs catalog B from its real pinned upstream (about 1 GB). Skipped unless
 * MODEL_DOWNLOAD_TEST_DIR is set, so CI never downloads it.
 */
class ModelDownloadInteropTest {
    @Test
    fun downloadsAndVerifiesTheFunAsrNanoCatalogEntry() {
        val dir = System.getenv("MODEL_DOWNLOAD_TEST_DIR")?.let(::File)
        assumeTrue(dir != null)
        val installer = LocalModelInstaller(dir!!)
        var last = 0L
        installer.install(
            ModelCatalogEntry.FunAsrNano,
            ModelSources.download(OkHttpClient(), ModelCatalogEntry.FunAsrNano),
            onProgress = { done, total ->
                if (done - last > 100_000_000 || done == total) {
                    last = done
                    println("downloaded $done / $total")
                }
            },
            attempts = 3
        )
        assertTrue(installer.isInstalled(LocalAsrModel.FunAsrNano))
    }
}
