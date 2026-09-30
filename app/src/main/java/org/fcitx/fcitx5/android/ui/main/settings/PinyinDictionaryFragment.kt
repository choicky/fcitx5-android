/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictionaryCatalog
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictionaryCatalogEntry
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.DictionaryStream
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.NaiveDustman
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.notificationManager
import org.fcitx.fcitx5.android.utils.queryFileName
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class PinyinDictionaryFragment : Fragment(), OnItemChangedListener<PinyinDictionary> {

    private val args by lazyRoute<SettingsRoute.PinyinDict>()

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var launcher: ActivityResultLauncher<String>

    private val dustman = NaiveDustman<Boolean>()

    private val busy: AtomicBoolean = AtomicBoolean(false)

    private val httpClient = OkHttpClient()

    private var dictionaryDownloadJob: Job? = null

    private var downloadUiJob: Job? = null
    private var downloadDialog: AlertDialog? = null
    private var downloadEntry: PinyinDictionaryCatalogEntry? = null
    private var downloadProgressView: ProgressBar? = null
    private var downloadDetailsView: TextView? = null
    private var downloadStatusView: TextView? = null
    @Volatile
    private var latestDownloadProgress = DownloadProgress(0, 0, 0.0, null)
    private var downloadState = DownloadState.Paused
    private var pauseRequested = false
    private var cancelRequested = false

    private var uiInitialized = false

    private val ui: BaseDynamicListUi<PinyinDictionary> by lazy {
        object : BaseDynamicListUi<PinyinDictionary>(
            requireContext(),
            Mode.Custom(),
            PinyinDictManager.listDictionaries(),
            initCheckBox = { entry ->
                if (entry is LibIMEDictionary) {
                    isChecked = entry.isEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) entry.enable() else entry.disable()
                        ui.updateItem(ui.indexItem(entry), entry)
                    }
                } else {
                    isChecked = true
                    isEnabled = false
                }
            }
        ) {
            init {
                enableUndo = false
                addTouchCallback()
                // since FAB is always shown in this fragment,
                // set shouldShowFab to true to hide it when entering multi select mode
                shouldShowFab = true
                fab.setOnClickListener {
                    showAddOptions()
                }
                setViewModel(viewModel)
                removable = { e -> e !is BuiltinDictionary }
            }

            override fun updateFAB() {
                // do nothing
            }

            override fun showEntry(x: PinyinDictionary): String = x.name
        }.also {
            uiInitialized = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        createNotificationChannel()
        registerLauncher()
        ui.addOnItemChangedListener(this)
        resetDustman()
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        args.uri?.let { importFromUri(Uri.parse(it)) }
        super.onViewCreated(view, savedInstanceState)
        viewModel.toolbarButton.value =
            if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getText(R.string.pinyin_dict),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            requireContext().notificationManager.createNotificationChannel(channel)
        }
    }

    private fun registerLauncher() {
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                importFromUri(uri)
        }
    }

    private fun showAddOptions() {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf(getString(R.string.import_), getString(R.string.download_dictionary))) {
                    _: DialogInterface, which: Int ->
                if (which == 0) launcher.launch("*/*") else showCatalog()
            }
            .show()
    }

    private fun showCatalog() {
        val entries = PinyinDictionaryCatalog.entries
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_dictionary)
            .setItems(entries.map { catalogLabel(it) }.toTypedArray()) {
                    _: DialogInterface, which: Int ->
                showCatalogEntry(entries[which])
            }
            .show()
    }

    private fun catalogLabel(entry: PinyinDictionaryCatalogEntry): String {
        val staged = PinyinDictManager.stagedCatalogBytes(entry)
        return when {
            staged > 0 -> "${entry.displayName} · ${formatBytes(staged)} / " +
                "${formatBytes(entry.size)} · ${getString(R.string.resume_download)}"
            else -> "${entry.displayName} · ${entry.license}"
        }
    }

    private fun showCatalogEntry(entry: PinyinDictionaryCatalogEntry) {
        AlertDialog.Builder(requireContext())
            .setTitle(entry.displayName)
            .setMessage(
                "${entry.version}\n\n" +
                    "${getString(R.string.dictionary_license)}: ${entry.license}\n" +
                    "${getString(R.string.dictionary_source)}: ${entry.sourceRepository}\n" +
                    "${getString(R.string.dictionary_limitations)}: ${entry.limitations}"
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_dictionary) { _, _ ->
                downloadCatalogEntry(entry)
            }
            .show()
    }

    private fun downloadCatalogEntry(entry: PinyinDictionaryCatalogEntry) {
        showDownloadDialog(entry, startImmediately = true)
    }

    private fun showDownloadDialog(
        entry: PinyinDictionaryCatalogEntry,
        startImmediately: Boolean
    ) {
        val ctx = requireContext()
        val nm = ctx.notificationManager
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 0)
        }
        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        val status = TextView(ctx)
        val details = TextView(ctx)
        content.addView(progress)
        content.addView(status)
        content.addView(details)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(entry.displayName)
            .setView(content)
            .setNegativeButton(R.string.pause_download, null)
            .setPositiveButton(R.string.cancel_download, null)
            .create()
        downloadDialog = dialog
        downloadEntry = entry
        downloadProgressView = progress
        downloadStatusView = status
        downloadDetailsView = details
        downloadState = if (PinyinDictManager.stagedCatalogBytes(entry) > 0) {
            DownloadState.Paused
        } else {
            DownloadState.Downloading
        }
        dialog.setOnShowListener {
            updateDownloadDialog()
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                if (downloadState == DownloadState.Downloading) pauseDownload()
                else startDownload(entry)
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                cancelDownload()
            }
            if (startImmediately) startDownload(entry)
        }
        dialog.setOnDismissListener {
            if (!cancelRequested && downloadState == DownloadState.Downloading) {
                pauseRequested = true
                dictionaryDownloadJob?.cancel()
            }
            downloadUiJob?.cancel()
            downloadDialog = null
            downloadEntry = null
            downloadProgressView = null
            downloadStatusView = null
            downloadDetailsView = null
        }
        dialog.show()
    }

    private fun startDownload(entry: PinyinDictionaryCatalogEntry) {
        if (dictionaryDownloadJob?.isActive == true) return
        pauseRequested = false
        cancelRequested = false
        downloadState = DownloadState.Downloading
        latestDownloadProgress = DownloadProgress(
            PinyinDictManager.stagedCatalogBytes(entry), entry.size, 0.0, null
        )
        updateDownloadDialog()
        downloadUiJob?.cancel()
        downloadUiJob = lifecycleScope.launch {
            while (isActive) {
                updateDownloadDialog()
                delay(250)
            }
        }
        val ctx = requireContext()
        val nm = ctx.notificationManager
        dictionaryDownloadJob = lifecycleScope.launch {
            val id = IMPORT_ID++
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                .setContentTitle(getString(R.string.pinyin_dict))
                .setContentText("${getString(R.string.downloading)} ${entry.displayName}")
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build().let { nm.notify(id, it) }
            try {
                val installed = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(entry.url).build()
                    val coroutineContext = currentCoroutineContext()
                    PinyinDictManager.installCatalogEntry(
                        entry,
                        source = { offset ->
                            val rangedRequest = request.newBuilder().apply {
                                if (offset > 0) header("Range", "bytes=$offset-")
                            }.build()
                            val call = httpClient.newCall(rangedRequest)
                            coroutineContext.job.invokeOnCompletion { call.cancel() }
                            val response = call.execute()
                            if (!response.isSuccessful) {
                                response.close()
                                throw IOException("HTTP ${response.code}")
                            }
                            val actualOffset = if (response.code == 206) offset else 0
                            val body = response.body!!
                            val contentLength = body.contentLength().takeIf { it >= 0 }
                            DictionaryStream(
                                body.byteStream(),
                                actualOffset,
                                contentLength?.let { it + actualOffset }
                            )
                        },
                        cancelled = { !coroutineContext.isActive },
                        progress = { done, total ->
                            updateDownloadProgress(done, total)
                        },
                        verifying = {
                            downloadState = DownloadState.Verifying
                        }
                    ).getOrThrow()
                }
                ui.entries.indexOfFirst { it.name == installed.name }.let { index ->
                    if (index >= 0) ui.updateItem(index, installed)
                    else ui.addItem(item = installed)
                }
                downloadState = DownloadState.Installed
            } catch (e: Exception) {
                if (pauseRequested) {
                    downloadState = DownloadState.Paused
                } else if (cancelRequested) {
                    // Explicit cancellation has already removed the staging file.
                } else if (isActive) {
                    downloadState = DownloadState.Failed
                    updateDownloadDialog(e)
                }
            } finally {
                nm.cancel(id)
                downloadUiJob?.cancel()
                if (downloadState == DownloadState.Installed && !cancelRequested) {
                    downloadDialog?.dismiss()
                }
                dictionaryDownloadJob = null
                updateDownloadDialog()
            }
        }
    }

    private fun pauseDownload() {
        pauseRequested = true
        downloadState = DownloadState.Paused
        dictionaryDownloadJob?.cancel()
        updateDownloadDialog()
    }

    private fun cancelDownload() {
        cancelRequested = true
        pauseRequested = false
        val entry = downloadEntry
        dictionaryDownloadJob?.let { job ->
            job.invokeOnCompletion { entry?.let { PinyinDictManager.discardCatalogDownload(it) } }
            job.cancel()
        } ?: entry?.let { PinyinDictManager.discardCatalogDownload(it) }
        downloadState = DownloadState.Paused
        downloadDialog?.dismiss()
    }

    private fun updateDownloadProgress(done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        val old = latestDownloadProgress
        val elapsed = max(1L, now - (old.timestamp ?: now))
        val instant = if (old.timestamp == null) 0.0 else
            (done - old.done).coerceAtLeast(0) * 1000.0 / elapsed
        val speed = if (old.speed > 0 && instant > 0) {
            old.speed * 0.8 + instant * 0.2
        } else instant
        val eta = if (speed > 0 && total > done) ((total - done) / speed).toLong() else null
        latestDownloadProgress = DownloadProgress(done, total, speed, now, eta)
    }

    private fun updateDownloadDialog(failure: Exception? = null) {
        if (!isAdded) return
        val progress = latestDownloadProgress
        downloadProgressView?.max = 1000
        downloadProgressView?.progress = if (progress.total > 0) {
            ((progress.done * 1000) / progress.total).coerceIn(0, 1000).toInt()
        } else 0
        downloadStatusView?.text = when (downloadState) {
            DownloadState.Downloading -> getString(R.string.downloading)
            DownloadState.Paused -> getString(R.string.download_paused)
            DownloadState.Verifying -> getString(R.string.download_verifying)
            DownloadState.Failed -> getString(
                R.string.download_failed,
                failure?.message ?: getString(R.string.download_interrupted)
            )
            DownloadState.Installed -> getString(R.string.download_installed)
        }
        val speed = if (progress.speed > 0) {
            "\n${formatBytes(progress.speed.toLong())}/s" +
                (progress.etaSeconds?.let { " · ${formatDuration(it)}" } ?: "")
        } else ""
        val percent = if (progress.total > 0) {
            "${((progress.done * 100) / progress.total).coerceIn(0, 100)}% · "
        } else ""
        downloadDetailsView?.text = "$percent${formatBytes(progress.done)} / " +
            "${formatBytes(progress.total)}$speed"
        downloadDialog?.getButton(DialogInterface.BUTTON_NEGATIVE)?.apply {
            text = if (downloadState == DownloadState.Downloading) {
                getString(R.string.pause_download)
            } else getString(R.string.resume_download)
            isEnabled = downloadState != DownloadState.Verifying &&
                downloadState != DownloadState.Installed
        }
        downloadDialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled =
            downloadState != DownloadState.Verifying && downloadState != DownloadState.Installed
    }

    private fun formatBytes(bytes: Long): String =
        android.text.format.Formatter.formatFileSize(requireContext(), bytes)

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> getString(R.string.download_eta_seconds, seconds)
        else -> getString(R.string.download_eta_minutes, seconds / 60, seconds % 60)
    }
    private fun importFromUri(uri: Uri) {
        val ctx = requireContext()
        val cr = ctx.contentResolver
        val nm = ctx.notificationManager
        lifecycleScope.launch {
            val id = IMPORT_ID++
            val fileName = cr.queryFileName(uri) ?: return@launch
            if (PinyinDictionary.Type.fromFileName(fileName) == null) {
                ctx.importErrorDialog(R.string.invalid_dict)
                return@launch
            }
            val entryName = fileName.substringBeforeLast('.')
            if (ui.entries.any { it.name == entryName }) {
                ctx.importErrorDialog(R.string.dict_already_exists)
                return@launch
            }
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                .setContentTitle(getString(R.string.pinyin_dict))
                .setContentText("${getString(R.string.importing)} $entryName")
                .setOngoing(true)
                .setProgress(100, 0, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build().let { nm.notify(id, it) }
            try {
                val imported = withContext(Dispatchers.IO) {
                    val inputStream = cr.openInputStream(uri)!!
                    PinyinDictManager.importFromInputStream(inputStream, fileName).getOrThrow()
                }
                ui.addItem(item = imported)
            } catch (e: Exception) {
                ctx.importErrorDialog(e)
            }
            nm.cancel(id)
        }
    }

    private fun reloadDict() {
        if (!dustman.dirty) return
        resetDustman()
        // Save the reference to NotificationManager, because reloadDict() could be called
        // right before the Fragment detached from Activity, and at the time reload completes,
        // Fragment is no longer attached to a Context, thus unable to cancel the notification.
        val nm = requireContext().notificationManager
        lifecycleScope.launch {
            if (busy.compareAndSet(false, true)) {
                val id = RELOAD_ID++
                NotificationCompat.Builder(requireContext(), CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                    .setContentTitle(getString(R.string.pinyin_dict))
                    .setContentText(getString(R.string.reloading))
                    .setOngoing(true)
                    .setProgress(100, 0, true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build().let { nm.notify(id, it) }
                viewModel.fcitx.runOnReady {
                    reloadPinyinDict()
                }
                nm.cancel(id)
                busy.set(false)
            }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.mapNotNull { it as? LibIMEDictionary }
            .associate { it.name to it.isEnabled })
    }

    override fun onItemAdded(idx: Int, item: PinyinDictionary) {
        item as LibIMEDictionary
        dustman.addOrUpdate(item.name, item.isEnabled)
    }

    override fun onItemRemoved(idx: Int, item: PinyinDictionary) {
        item as LibIMEDictionary
        item.file.delete()
        dustman.remove(item.name)
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinDictionary>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: PinyinDictionary, new: PinyinDictionary) {
        new as LibIMEDictionary
        dustman.addOrUpdate(new.name, new.isEnabled)
    }

    override fun onStop() {
        reloadDict()
        if (uiInitialized) {
            ui.exitMultiSelect()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (uiInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

    companion object {
        private var RELOAD_ID = 0
        private var IMPORT_ID = 0
        const val CHANNEL_ID = "pinyin_dict"
    }

    private enum class DownloadState { Downloading, Paused, Verifying, Failed, Installed }

    private data class DownloadProgress(
        val done: Long,
        val total: Long,
        val speed: Double,
        val timestamp: Long? = null,
        val etaSeconds: Long? = null
    )
}
