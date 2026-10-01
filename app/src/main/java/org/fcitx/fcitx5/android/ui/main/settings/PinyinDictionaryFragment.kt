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
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
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
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictionaryConfig
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictionaryCatalog
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictionaryCatalogEntry
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.DictionaryStream
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.CatalogPlaceholderDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.StaticPinyinDictionary
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
import java.util.Locale
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
    private var privateImportEntry: PinyinDictionaryCatalogEntry? = null
    private var downloadProgressView: ProgressBar? = null
    private var downloadDetailsView: TextView? = null
    private var downloadStatusView: TextView? = null
    @Volatile
    private var latestDownloadProgress = DownloadProgress(0, 0, 0.0, null)
    private var downloadState = DownloadState.Paused
    private var pauseRequested = false
    private var cancelRequested = false
    private var extBEnabled = false

    private var uiInitialized = false

    private var managerRoot: FrameLayout? = null
    private var detailName: String? = null
    private var detailBack: OnBackPressedCallback? = null

    private val ui: DictionaryManagerUi by lazy {
        DictionaryManagerUi(requireContext(), initialEntries(), viewModel,
            extBEnabled = { extBEnabled },
            metadata = { row ->
                row.size?.let { size ->
                    row.entryCount?.let { count ->
                        getString(R.string.dictionary_metadata, formatBytes(size), formatCount(count))
                    } ?: formatBytes(size)
                }
            },
            toggle = ::setDictionaryEnabled,
            detail = ::showDictionaryDetail,
            add = ::showAddOptions,
        ).also { uiInitialized = true }
    }

    private fun setDictionaryEnabled(entry: PinyinDictionary, enabled: Boolean) {
        when (entry) {
            is StaticPinyinDictionary -> setExtBEnabled(enabled)
            is LibIMEDictionary -> {
                if (enabled) entry.enable() else entry.disable()
                ui.updateItem(ui.indexItem(entry), entry)
            }
        }
    }

    private fun initialEntries(): List<PinyinDictionary> {
        val installed = PinyinDictManager.listDictionaries()
        val installedIds = installed.filterIsInstance<LibIMEDictionary>().map { it.name }.toSet()
        val catalog = PinyinDictionaryCatalog.entries
            .filterNot { it.id in installedIds }
            .map { CatalogPlaceholderDictionary(it.id, it.displayName) }
        return listOf(
            StaticPinyinDictionary(
                StaticPinyinDictionary.Kind.Base,
                getString(R.string.dictionary_builtin_base),
                "LibIME Simplified Chinese Base"
            ),
            StaticPinyinDictionary(
                StaticPinyinDictionary.Kind.ExtensionB,
                getString(R.string.dictionary_builtin_extb),
                "LibIME CJK Extension B"
            )
        ) + installed + catalog
    }

    private fun setExtBEnabled(enabled: Boolean) {
        if (enabled == extBEnabled) return
        lifecycleScope.launch {
            viewModel.fcitx.runOnReady {
                val config = PinyinDictionaryConfig.setExtBEnabled(
                    getImConfig("pinyin"), enabled
                )
                setImConfig("pinyin", config)
                extBEnabled = enabled
            }
            if (uiInitialized) ui.refreshState()
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
        detailName = savedInstanceState?.getString("dictionary_detail")
        (ui.root.parent as? ViewGroup)?.removeView(ui.root)
        ui.root.visibility = View.VISIBLE
        return FrameLayout(requireContext()).also {
            managerRoot = it
            it.addView(ui.root)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        args.uri?.let { importFromUri(Uri.parse(it)) }
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launch {
            viewModel.fcitx.runOnReady {
                extBEnabled = PinyinDictionaryConfig.extBEnabled(
                    getImConfig("pinyin")
                )
            }
            ui.refreshState()
        }
        viewModel.toolbarButton.value = ButtonMode.EDIT
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = {
                    if (detailName == null) ui.enterMultiSelect(requireActivity().onBackPressedDispatcher)
                },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
        val restoredDetail = detailName?.let { name -> ui.entries.find { it.name == name } }
        detailName = null
        restoredDetail?.let(::showDictionaryDetail)
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

    private fun showDictionaryDetail(dictionary: PinyinDictionary) {
        val root = managerRoot ?: return
        val row = DictionaryPresentation.row(dictionary, extBEnabled)
        if (!row.manageable) return
        detailName = dictionary.name
        ui.exitMultiSelect()
        viewModel.toolbarButton.value = ButtonMode.NONE
        detailBack?.remove()
        detailBack = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeDictionaryDetail()
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        val ctx = requireContext()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String, heading: Boolean = false) {
            body.addView(TextView(ctx).apply {
                text = value
                setTextAppearance(if (heading) android.R.style.TextAppearance_Material_Title
                    else android.R.style.TextAppearance_Material_Body1)
                setPadding(0, padding / 2, 0, padding / 2)
                setTextIsSelectable(!heading)
                if (heading) androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        fun field(label: Int, value: String?) {
            if (!value.isNullOrBlank()) text("${getString(label)}\n$value")
        }
        fun action(label: Int, block: () -> Unit) {
            body.addView(Button(ctx).apply {
                setText(label)
                setOnClickListener { block() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        text(row.name, heading = true)
        row.canonicalName?.let { text(it) }
        text(getString(if (row.installed) R.string.download_installed else R.string.dictionary_not_installed))
        if (row.enabled != null) {
            text(getString(R.string.dictionary_section_use), heading = true)
            body.addView(SwitchCompat(ctx).apply {
                setText(R.string.dictionary_use)
                isChecked = row.enabled
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnCheckedChangeListener { _, checked -> setDictionaryEnabled(dictionary, checked) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        row.catalog?.let { catalog ->
            text(getString(R.string.dictionary_section_information), heading = true)
            field(R.string.dictionary_entries, catalog.entryCount?.let(::formatCount))
            field(R.string.dictionary_size, formatBytes(catalog.size))
            field(R.string.dictionary_catalog_version, catalog.version)
            field(R.string.dictionary_source_revision, catalog.sourceRevision)
            text(getString(R.string.dictionary_section_source_license), heading = true)
            field(R.string.dictionary_source, catalog.sourceRepository)
            action(R.string.dictionary_source_project) { openExternalLink(catalog.sourceRepository) }
            field(R.string.dictionary_license, catalog.license)
            action(R.string.dictionary_license_info) { openExternalLink(catalog.licenseUrl) }
            text(catalog.attribution)
            text(catalog.modificationStatement)
            text(getString(R.string.dictionary_normalization_notice))
            field(R.string.dictionary_limitations, catalog.limitations)
            if (!row.installed) {
                action(if (catalog.privateImportOnly) R.string.import_research_dictionary
                    else R.string.download_dictionary) { showCatalogEntry(catalog) }
            }
        }
        if (row.installed) {
            text(getString(R.string.dictionary_section_storage), heading = true)
            field(R.string.dictionary_local_size, formatBytes(dictionary.file.length()))
            field(R.string.dictionary_filename, dictionary.file.name)
        }
        if (row.removable) action(R.string.delete) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.dictionary_delete_confirmation, row.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    ui.indexItem(dictionary).takeIf { it >= 0 }?.let { ui.removeItem(it) }
                    closeDictionaryDetail()
                }.show()
        }
        // Keep the list mounted: existing import/delete feedback uses it as the
        // Snackbar anchor even while its detail is displayed.
        while (root.childCount > 1) root.removeViewAt(root.childCount - 1)
        ui.root.visibility = View.GONE
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true
            clipToPadding = false
            addView(body)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                view.setPadding(0, 0, 0, bottom)
                insets
            }
        }, FrameLayout.LayoutParams(-1, -1))
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun closeDictionaryDetail() {
        detailBack?.remove()
        detailName = null
        managerRoot?.let { root ->
            while (root.childCount > 1) root.removeViewAt(root.childCount - 1)
            ui.root.visibility = View.VISIBLE
            ui.refreshState()
            androidx.core.view.ViewCompat.requestApplyInsets(root)
        }
        viewModel.toolbarButton.value = ButtonMode.EDIT
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("dictionary_detail", detailName)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        detailBack?.remove()
        managerRoot = null
        super.onDestroyView()
    }

    private fun registerLauncher() {
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val expected = privateImportEntry.also { privateImportEntry = null }
            if (uri != null) importFromUri(uri, expected)
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
        val label = "${entry.displayName} / ${entry.canonicalName}"
        return when {
            staged > 0 -> "$label · ${formatBytes(staged)} / " +
                "${formatBytes(entry.size)} · ${entry.entryCount?.let { formatCount(it) } ?: ""} " +
                "· ${getString(R.string.resume_download)}"
            else -> "$label · ${dictionarySummary(entry, entry.size)}"
        }
    }

    private fun showCatalogEntry(entry: PinyinDictionaryCatalogEntry, allowDownload: Boolean = true) {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 0)
        }
        val details = TextView(ctx).apply {
            text = buildCatalogDetails(entry)
        }
        content.addView(details)
        val links = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        links.addView(Button(ctx).apply {
            text = getString(R.string.dictionary_source_project)
            setOnClickListener { openExternalLink(entry.sourceRepository) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        links.addView(Button(ctx).apply {
            text = getString(R.string.dictionary_license_info)
            setOnClickListener { openExternalLink(entry.licenseUrl) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(links)
        val builder = AlertDialog.Builder(ctx)
            .setTitle("${entry.displayName}\n${entry.canonicalName}")
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
        builder.setPositiveButton(
            if (allowDownload) {
                if (entry.privateImportOnly) R.string.import_research_dictionary
                else R.string.download_dictionary
            } else android.R.string.ok
        ) { _, _ ->
            if (allowDownload) downloadCatalogEntry(entry)
        }
        builder
            .show()
    }

    private fun buildCatalogDetails(entry: PinyinDictionaryCatalogEntry): String =
        "${getString(R.string.dictionary_third_party)}\n\n" +
            "${getString(R.string.dictionary_source)}: ${entry.sourceRepository}\n" +
            "${getString(R.string.dictionary_catalog_version)}: ${entry.version}\n" +
            "${getString(R.string.dictionary_license)}: ${entry.license}\n" +
            "${dictionarySummary(entry, entry.size)}\n\n" +
            "${entry.attribution}\n\n" +
            "${entry.modificationStatement}\n" +
            "${getString(R.string.dictionary_normalization_notice)}\n\n" +
            "${getString(R.string.dictionary_limitations)}: ${entry.limitations}"

    private fun openExternalLink(url: String) {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun downloadCatalogEntry(entry: PinyinDictionaryCatalogEntry) {
        if (entry.privateImportOnly) {
            privateImportEntry = entry
            launcher.launch("*/*")
        } else {
            showDownloadDialog(entry, startImmediately = true)
        }
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
            .setNegativeButton(R.string.cancel_download, null)
            .setPositiveButton(R.string.pause_download, null)
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
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (downloadState == DownloadState.Downloading) pauseDownload()
                else startDownload(entry)
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
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
        downloadDialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.apply {
            text = if (downloadState == DownloadState.Downloading) {
                getString(R.string.pause_download)
            } else getString(R.string.resume_download)
            isEnabled = downloadState != DownloadState.Verifying &&
                downloadState != DownloadState.Installed
        }
        downloadDialog?.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled =
            downloadState != DownloadState.Verifying && downloadState != DownloadState.Installed
    }

    private fun formatBytes(bytes: Long): String =
        android.text.format.Formatter.formatFileSize(requireContext(), bytes)

    private fun dictionarySummary(entry: PinyinDictionaryCatalogEntry, bytes: Long): String {
        val count = entry.entryCount?.let { formatCount(it) }
            ?: getString(R.string.dictionary_entry_count_unknown)
        return getString(R.string.dictionary_metadata, formatBytes(bytes), count)
    }

    private fun formatCount(count: Long): String =
        String.format(Locale.getDefault(), "%,d", count)

    private fun formatDuration(seconds: Long): String = when {
        seconds < 60 -> getString(R.string.download_eta_seconds, seconds)
        else -> getString(R.string.download_eta_minutes, seconds / 60, seconds % 60)
    }
    private fun importFromUri(uri: Uri, expected: PinyinDictionaryCatalogEntry? = null) {
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
            if (expected != null) {
                if (fileName != expected.fileName) {
                    ctx.importErrorDialog(R.string.invalid_dict)
                    return@launch
                }
                val valid = withContext(Dispatchers.IO) {
                    cr.openInputStream(uri)?.use { input ->
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            digest.update(buffer, 0, count)
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        size == expected.size && hash == expected.sha256
                    } ?: false
                }
                if (!valid) {
                    ctx.importErrorDialog(R.string.dictionary_integrity_error)
                    return@launch
                }
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
        val libime = item as? LibIMEDictionary ?: return
        dustman.addOrUpdate(libime.name, libime.isEnabled)
        if (detailName == libime.name) showDictionaryDetail(libime)
    }

    override fun onItemRemoved(idx: Int, item: PinyinDictionary) {
        val libime = item as? LibIMEDictionary ?: return
        libime.file.delete()
        dustman.remove(libime.name)
        PinyinDictionaryCatalog.find(libime.name)?.let {
            ui.addItem(item = CatalogPlaceholderDictionary(it.id, it.displayName))
        }
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinDictionary>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: PinyinDictionary, new: PinyinDictionary) {
        val libime = new as? LibIMEDictionary ?: return
        dustman.addOrUpdate(libime.name, libime.isEnabled)
        if (old !== new && detailName == libime.name) showDictionaryDetail(libime)
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
