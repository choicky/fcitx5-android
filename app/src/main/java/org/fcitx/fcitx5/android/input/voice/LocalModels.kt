/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Where Local models live. The Model Manager installs into the app's no-backup directory (not
 * backed up, not in the user-data export); a complete model pushed with adb to the older
 * external location is still used, so earlier test setups keep working.
 */
internal object LocalModels {

    fun root(context: Context) = context.noBackupFilesDir.resolve("local-asr")

    fun installer(context: Context) = LocalModelInstaller(root(context))

    private fun legacyDir(context: Context, model: LocalAsrModel) =
        LocalAsrBackend.modelDir(context.getExternalFilesDir(null), model)

    fun dir(context: Context, model: LocalAsrModel): File =
        installer(context).activeDir(model, legacyDir(context, model))

    fun isInstalled(context: Context, model: LocalAsrModel) =
        model.missingFiles(dir(context, model)).isEmpty()

    /** Also removes an adb-pushed copy, which [dir] would otherwise keep using. */
    fun remove(context: Context, model: LocalAsrModel) =
        installer(context).remove(model, legacyDir(context, model))

    /** Bytes of an unfinished download that a retry resumes from. */
    fun stagedBytes(context: Context, model: LocalAsrModel) = installer(context).stagedBytes(model)
}

/**
 * Model downloads and imports, kept running while the settings screen is closed (for the life of
 * the process). One job per model; listeners are called on the main thread.
 */
internal object ModelJobs {

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private val tasks = ModelTasks(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        main.post { synchronized(this) { listeners.toList() }.forEach { it() } }
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun state(model: LocalAsrModel): ModelTasks.State? = tasks.state(model)

    fun addListener(listener: () -> Unit) = synchronized(this) { listeners += listener }

    fun removeListener(listener: () -> Unit) = synchronized(this) { listeners -= listener }

    /** Occupied until the worker has exited, also while a cancelled download is stopping. */
    fun isRunning(model: LocalAsrModel) = tasks.isBusy(model)

    /**
     * From the catalog's pinned source, or from [base] the user entered; the pinned SHA-256
     * applies either way. Plain HTTP only in debug builds.
     */
    fun download(context: Context, entry: ModelCatalogEntry, base: String? = entry.downloadBase): Boolean {
        // A's download is a test-build exception (D037); a release build never starts it
        if (!entry.downloadOffered(BuildConfig.DEBUG)) return false
        return run(context, entry, attempts = 3) { handle ->
            // cancelling also cancels the HTTP call, so a blocking read ends promptly
            ModelSources.download(http, entry, base, allowCleartext = BuildConfig.DEBUG) { call ->
                handle.onCancel(call::cancel)
            }
        }
    }

    /** Import files the user picked; they are matched by name and checked like a download. */
    fun import(context: Context, entry: ModelCatalogEntry, uris: List<Uri>): Boolean {
        val resolver = context.applicationContext.contentResolver
        val byName = uris.associateBy { uri ->
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }
        val source: ModelFileSource = { file, _ ->
            byName[file.path.substringAfterLast('/')]?.let(resolver::openInputStream)?.let { ModelStream(it) }
        }
        return run(context, entry, attempts = 1) { source }
    }

    fun cancel(model: LocalAsrModel) = tasks.cancel(model)

    /** False while the model is still occupied, for example by a download that is stopping. */
    private fun run(
        context: Context,
        entry: ModelCatalogEntry,
        attempts: Int,
        source: (ModelTasks.Handle) -> ModelFileSource
    ): Boolean {
        val installer = LocalModels.installer(context.applicationContext)
        return tasks.start(entry.model, entry.totalBytes) { handle ->
            try {
                installer.install(
                    entry, source(handle),
                    onProgress = handle::progress,
                    cancelled = { handle.cancelled },
                    attempts = attempts
                )
            } catch (e: Exception) {
                Timber.w("Local model ${entry.model.name} install failed: ${ErrorRedaction.redact(e.message.orEmpty())}")
                throw e
            }
        }
    }
}
