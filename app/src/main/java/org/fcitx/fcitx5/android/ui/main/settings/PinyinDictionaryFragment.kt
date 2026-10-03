/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.NaiveDustman
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.notificationManager
import org.fcitx.fcitx5.android.utils.queryFileName
import java.util.concurrent.atomic.AtomicBoolean
import splitties.dimensions.dp

class PinyinDictionaryFragment : Fragment(), OnItemChangedListener<PinyinDictionary> {
    private val args by lazyRoute<SettingsRoute.PinyinDict>()
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var launcher: ActivityResultLauncher<String>
    private val dustman = NaiveDustman<Boolean>()
    private val busy = AtomicBoolean(false)
    private var ui: DictionaryManagerUi? = null
    private var managerRoot: FrameLayout? = null
    private var detailName: String? = null
    private var detailBack: OnBackPressedCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        createNotificationChannel(); registerLauncher()
        val manager = DictionaryManagerUi(
            requireContext(), PinyinDictManager.listDictionaries(), viewModel,
            toggle = ::setDictionaryEnabled,
            detail = ::showDictionaryDetail,
            add = { launcher.launch("*/*") }
        )
        ui = manager
        manager.addOnItemChangedListener(this); resetDustman()
        return FrameLayout(requireContext()).also { root ->
            managerRoot = root; root.addView(manager.root, FrameLayout.LayoutParams(-1, -1))
        }
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        args.uri?.let { importFromUri(Uri.parse(it)) }
        super.onViewCreated(view, state)
        viewModel.toolbarButton.value = if (ui!!.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        requireActivity().addMenuProvider(EditDeleteMenuProvider(
            buttonMode = viewModel.toolbarButton,
            editButtonAction = { ui!!.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
            deleteButtonAction = { ui!!.deleteSelected(); ui!!.exitMultiSelect() },
            menuHost = requireActivity(), lifecycleOwner = viewLifecycleOwner
        ), viewLifecycleOwner, Lifecycle.State.STARTED)
        state?.getString(DETAIL_KEY)?.let { name -> ui!!.entries.firstOrNull { it.name == name }?.let(::showDictionaryDetail) }
    }
    private fun setDictionaryEnabled(dictionary: PinyinDictionary, enabled: Boolean) {
        val item = dictionary as? LibIMEDictionary ?: return
        if (enabled) item.enable() else item.disable()
        ui!!.updateItem(ui!!.indexItem(item), item); dustman.addOrUpdate(item.name, item.isEnabled)
    }
    private fun showDictionaryDetail(dictionary: PinyinDictionary) {
        val root = managerRoot ?: return
        detailName = dictionary.name; ui!!.root.visibility = View.GONE; detailBack?.remove()
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(requireContext().dp(32), requireContext().dp(32), requireContext().dp(32), requireContext().dp(32))
        }
        content.addView(TextView(requireContext()).apply { text = getString(R.string.dictionary_detail); textSize = 20f })
        content.addView(TextView(requireContext()).apply { text = dictionary.name; textSize = 18f })
        content.addView(TextView(requireContext()).apply { text = getString(R.string.dictionary_file, dictionary.file.name) })
        content.addView(TextView(requireContext()).apply { text = getString(R.string.dictionary_type, dictionary.type.name) })
        content.addView(TextView(requireContext()).apply { text = getString(R.string.dictionary_size, dictionary.file.length()) })
        if (dictionary is LibIMEDictionary) {
            content.addView(Switch(requireContext()).apply {
                text = getString(R.string.dictionary_use_named, dictionary.name); isChecked = dictionary.isEnabled
                setOnCheckedChangeListener { _, checked -> setDictionaryEnabled(dictionary, checked) }
            })
            content.addView(Button(requireContext()).apply {
                setText(R.string.delete)
                setOnClickListener {
                    AlertDialog.Builder(requireContext()).setMessage(
                        getString(R.string.dictionary_delete_message, dictionary.name)
                    ).setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.delete) { _, _ ->
                        ui!!.removeItem(ui!!.indexItem(dictionary)); closeDictionaryDetail()
                    }.show()
                }
            })
        }
        root.addView(ScrollView(requireContext()).apply { addView(content) }, FrameLayout.LayoutParams(-1, -1))
        detailBack = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeDictionaryDetail()
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
    }
    private fun closeDictionaryDetail() {
        val root = managerRoot ?: return
        if (root.childCount > 1) root.removeViews(1, root.childCount - 1)
        ui?.root?.visibility = View.VISIBLE; detailBack?.remove(); detailBack = null; detailName = null
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requireContext().notificationManager
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, getText(R.string.pinyin_dict), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = CHANNEL_ID })
    }
    private fun registerLauncher() {
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::importFromUri) }
    }
    private fun importFromUri(uri: Uri) {
        val ctx = requireContext(); val cr = ctx.contentResolver; val nm = ctx.notificationManager
        lifecycleScope.launch {
            val id = IMPORT_ID++; val fileName = cr.queryFileName(uri) ?: return@launch
            if (PinyinDictionary.Type.fromFileName(fileName) == null) { ctx.importErrorDialog(R.string.invalid_dict); return@launch }
            val entryName = fileName.substringBeforeLast('.')
            if (ui?.entries?.any { it.name == entryName } == true) { ctx.importErrorDialog(R.string.dict_already_exists); return@launch }
            NotificationCompat.Builder(ctx, CHANNEL_ID).setSmallIcon(R.drawable.ic_baseline_library_books_24)
                .setContentTitle(getString(R.string.pinyin_dict)).setContentText("${getString(R.string.importing)} $entryName")
                .setOngoing(true).setProgress(100, 0, true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
                .let { nm.notify(id, it) }
            try {
                val imported = withContext(Dispatchers.IO) {
                    PinyinDictManager.importFromInputStream(cr.openInputStream(uri)!!, fileName).getOrThrow()
                }
                ui?.addItem(item = imported)
            } catch (e: Exception) { ctx.importErrorDialog(e) }
            nm.cancel(id)
        }
    }
    private fun reloadDict() {
        if (!dustman.dirty) return; resetDustman(); val nm = requireContext().notificationManager
        lifecycleScope.launch {
            if (busy.compareAndSet(false, true)) {
                val id = RELOAD_ID++
                NotificationCompat.Builder(requireContext(), CHANNEL_ID).setSmallIcon(R.drawable.ic_baseline_library_books_24)
                    .setContentTitle(getString(R.string.pinyin_dict)).setContentText(getString(R.string.reloading))
                    .setOngoing(true).setProgress(100, 0, true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
                    .let { nm.notify(id, it) }
                viewModel.fcitx.runOnReady { reloadPinyinDict() }; nm.cancel(id); busy.set(false)
            }
        }
    }
    private fun resetDustman() {
        dustman.reset(ui?.entries.orEmpty().mapNotNull { it as? LibIMEDictionary }
            .associate { it.name to it.isEnabled })
    }
    override fun onItemAdded(idx: Int, item: PinyinDictionary) {
        (item as? LibIMEDictionary)?.let { dustman.addOrUpdate(it.name, it.isEnabled) }
    }
    override fun onItemRemoved(idx: Int, item: PinyinDictionary) {
        (item as? LibIMEDictionary)?.let { it.file.delete(); dustman.remove(it.name) }
    }
    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinDictionary>>) { batchRemove(indexed) }
    override fun onItemUpdated(idx: Int, old: PinyinDictionary, new: PinyinDictionary) {
        (new as? LibIMEDictionary)?.let { dustman.addOrUpdate(it.name, it.isEnabled) }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString(DETAIL_KEY, detailName); super.onSaveInstanceState(outState) }
    override fun onStop() { reloadDict(); ui?.exitMultiSelect(); super.onStop() }
    override fun onDestroyView() {
        closeDictionaryDetail(); ui?.removeItemChangedListener(); ui = null; managerRoot = null
        super.onDestroyView()
    }
    companion object {
        private var RELOAD_ID = 0; private var IMPORT_ID = 0
        private const val DETAIL_KEY = "dictionary_detail"
        const val CHANNEL_ID = "pinyin_dict"
    }
}
