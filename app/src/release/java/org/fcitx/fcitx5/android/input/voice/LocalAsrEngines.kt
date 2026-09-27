/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import java.io.File

/** Release builds ship no Local ASR runtime (the Phase 4B.3b-1 PoC is debug-only). */
internal object LocalAsrEngines {

    const val AVAILABLE = false

    fun load(model: LocalAsrModel, modelDir: File, threads: Int): LocalAsrRecognizer =
        throw UnsupportedOperationException("Local ASR is only available in debug builds")
}
