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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Where Local models live. The Model Manager installs into the app's no-backup directory (not
 * backed up, not in the user-data export); a complete model pushed with adb to the older
 * external location is still used, so earlier test setups keep working.
 */
internal object LocalModels {

    fun root(context: Context) = context.noBackupFilesDir.resolve("local-asr")

    fun installer(context: Context) = LocalModelInstaller(root(context))

    fun dir(context: Context, model: LocalAsrModel): File {
        val installed = root(context).resolve(model.dirName)
        if (model.missingFiles(installed).isEmpty()) return installed
        val legacy = LocalAsrBackend.modelDir(context.getExternalFilesDir(null), model)
        if (legacy != null && model.missingFiles(legacy).isEmpty()) return legacy
        return installed
    }

    fun isInstalled(context: Context, model: LocalAsrModel) =
        model.missingFiles(dir(context, model)).isEmpty()
}

/**
 * Model downloads and imports, kept running while the settings screen is closed (for the life of
 * the process). One job per model; listeners are called on the main thread.
 */
internal object ModelJobs {

    sealed interface State {
        data class Running(val done: Long, val total: Long) : State
        data class Failed(val reason: InstallFailure?, val detail: String) : State
        data object Finished : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val jobs = mutableMapOf<LocalAsrModel, Job>()
    private val states = mutableMapOf<LocalAsrModel, State>()
    private val listeners = mutableSetOf<() -> Unit>()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized
    fun state(model: LocalAsrModel): State? = states[model]

    fun addListener(listener: () -> Unit) = synchronized(this) { listeners += listener }

    fun removeListener(listener: () -> Unit) = synchronized(this) { listeners -= listener }

    @Synchronized
    fun isRunning(model: LocalAsrModel) = jobs[model]?.isActive == true

    fun download(context: Context, entry: ModelCatalogEntry) =
        run(context, entry, ModelSources.download(http, entry), attempts = 3)

    /** Import files the user picked; they are matched by name and checked like a download. */
    fun import(context: Context, entry: ModelCatalogEntry, uris: List<Uri>) {
        val resolver = context.applicationContext.contentResolver
        val byName = uris.associateBy { uri ->
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }
        val source: (ModelFile) -> InputStream? = { file ->
            byName[file.path.substringAfterLast('/')]?.let(resolver::openInputStream)
        }
        run(context, entry, source, attempts = 1)
    }

    @Synchronized
    fun cancel(model: LocalAsrModel) {
        jobs[model]?.cancel()
    }

    @Synchronized
    private fun run(
        context: Context,
        entry: ModelCatalogEntry,
        source: (ModelFile) -> InputStream?,
        attempts: Int
    ) {
        val model = entry.model
        if (jobs[model]?.isActive == true) return
        val installer = LocalModels.installer(context.applicationContext)
        update(model, State.Running(0, entry.totalBytes))
        val job = scope.launch {
            val result = runCatching {
                installer.install(
                    entry, source,
                    onProgress = { done, total -> update(model, State.Running(done, total)) },
                    cancelled = { !isActive },
                    attempts = attempts
                )
            }
            update(
                model, result.fold(
                    { State.Finished },
                    {
                        Timber.w("Local model ${model.name} install failed: ${it.message}")
                        State.Failed(it as? InstallFailure, it.message.orEmpty())
                    }
                )
            )
        }
        jobs[model] = job
    }

    private fun update(model: LocalAsrModel, state: State) {
        val notify = synchronized(this) {
            val previous = states[model]
            states[model] = state
            // progress is reported at most once per percent
            !(previous is State.Running && state is State.Running &&
                    state.done * 100 / state.total.coerceAtLeast(1) ==
                    previous.done * 100 / previous.total.coerceAtLeast(1))
        }
        if (notify) main.post { synchronized(this) { listeners.toList() }.forEach { it() } }
    }
}
