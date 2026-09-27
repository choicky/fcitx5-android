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
 * Installs a downloadable catalog entry from its real pinned upstream (B is about 1 GB).
 * Skipped unless MODEL_DOWNLOAD_TEST_DIR is set, so CI never downloads it;
 * MODEL_DOWNLOAD_TEST_MODEL picks the entry (default FunAsrNano).
 */
class ModelDownloadInteropTest {
    @Test
    fun downloadsAndVerifiesACatalogEntry() {
        val dir = System.getenv("MODEL_DOWNLOAD_TEST_DIR")?.let(::File)
        assumeTrue(dir != null)
        val model = LocalAsrModel.valueOf(System.getenv("MODEL_DOWNLOAD_TEST_MODEL") ?: "FunAsrNano")
        val entry = ModelCatalogEntry.of(model)
        val installer = LocalModelInstaller(dir!!)
        var last = 0L
        installer.install(
            entry,
            ModelSources.download(OkHttpClient(), entry),
            onProgress = { done, total ->
                if (done - last > 100_000_000 || done == total) {
                    last = done
                    println("downloaded $done / $total")
                }
            },
            attempts = 3
        )
        assertTrue(installer.isInstalled(model))
    }
}
