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
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class PinyinDictionaryFragment : Fragment(), OnItemChangedListener<PinyinDictionary> {

    private val args by lazyRoute<SettingsRoute.PinyinDict>()

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var launcher: ActivityResultLauncher<String>

    private val dustman = NaiveDustman<Boolean>()

    private val busy: AtomicBoolean = AtomicBoolean(false)

    private val httpClient = OkHttpClient()

    private var dictionaryDownloadJob: Job? = null

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
            .setItems(entries.map { "${it.displayName} · ${it.license}" }.toTypedArray()) {
                    _: DialogInterface, which: Int ->
                showCatalogEntry(entries[which])
            }
            .show()
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
        val ctx = requireContext()
        val nm = ctx.notificationManager
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(entry.displayName)
            .setMessage(R.string.downloading)
            .setNegativeButton(R.string.pause_download, null)
            .create()
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
                            DictionaryStream(response.body!!.byteStream(), actualOffset)
                        },
                        cancelled = { !coroutineContext.isActive }
                    ).getOrThrow()
                }
                ui.entries.indexOfFirst { it.name == installed.name }.let { index ->
                    if (index >= 0) ui.updateItem(index, installed)
                    else ui.addItem(item = installed)
                }
            } catch (e: Exception) {
                if (isActive) ctx.importErrorDialog(e)
            } finally {
                nm.cancel(id)
                dialog.dismiss()
                dictionaryDownloadJob = null
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                dictionaryDownloadJob?.cancel()
            }
        }
        dialog.show()
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
}
