/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.app.AlertDialog
import android.content.ClipData
import android.os.Build
import android.view.View
import android.view.Gravity
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editorinfo.EditorInfoWindow
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.InputMethod
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.Keyboard
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ReloadConfig
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ThemeList
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ToolbarCollapse
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ToolbarCustomize
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

data class ToolbarControls(val isExpanded: () -> Boolean, val toggle: () -> Unit)

class StatusAreaWindow(
    private val toolbarControls: ToolbarControls? = null
) : InputWindow.ExtendedInputWindow<StatusAreaWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val editorInfoInspector by AppPrefs.getInstance().internal.editorInfoInspector

    private val staticEntries by lazy {
        arrayOf(
            StatusAreaEntry.Android(
                context.getString(R.string.theme),
                R.drawable.ic_baseline_palette_24,
                ThemeList
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.input_method_options),
                R.drawable.ic_baseline_language_24,
                InputMethod
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.reload_config),
                R.drawable.ic_baseline_sync_24,
                ReloadConfig
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                Keyboard
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.edit_toolbar),
                R.drawable.ic_baseline_edit_24,
                ToolbarCustomize
            )
        )
    }

    private fun toolbarEntry() = toolbarControls?.let {
        val toggle = ToolbarAction.toolbarToggle(it.isExpanded())
        StatusAreaEntry.Android(
            context.getString(if (toggle == ToolbarAction.ToolbarToggle.Collapse) R.string.collapse_toolbar else R.string.expand_toolbar),
            if (toggle == ToolbarAction.ToolbarToggle.Collapse) {
                R.drawable.ic_baseline_keyboard_arrow_left_24
            } else {
                R.drawable.ic_baseline_keyboard_arrow_right_24
            },
            ToolbarCollapse
        )
    }

    private fun showToolbarEditor() {
        val preference = AppPrefs.getInstance().internal.toolbarActions
        var actions = ToolbarAction.decode(preference.getValue())
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), 0, context.dp(20), 0)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content, ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            ))
        }
        val currentContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val availableContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(currentContainer)
        content.addView(availableContainer)
        content.addView(footer)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.edit_toolbar)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                preference.setValue(ToolbarAction.encode(actions))
                preference.fireChange()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.apply {
                attributes = attributes.apply { gravity = Gravity.BOTTOM }
                val keyboardPrefs = AppPrefs.getInstance().keyboard
                val measuredCharacterAreaHeight = windowManager.view.height
                val characterAreaHeight = if (measuredCharacterAreaHeight > 0) {
                    measuredCharacterAreaHeight
                } else {
                    val percent = if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        keyboardPrefs.keyboardHeightPercentLandscape.getValue()
                    } else {
                        keyboardPrefs.keyboardHeightPercent.getValue()
                    }
                    context.resources.displayMetrics.heightPixels * percent / 100
                }.coerceAtLeast(context.dp(180))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, characterAreaHeight)
            }
        }
        fun label(action: ToolbarAction) = context.getString(
            when (action) {
                ToolbarAction.Emoji -> R.string.emoji_and_symbols
                ToolbarAction.QuickPhrase -> R.string.quickphrase
                ToolbarAction.Voice -> R.string.voice_input
                ToolbarAction.Clipboard -> R.string.clipboard
                ToolbarAction.TextEditing -> R.string.text_editing
                ToolbarAction.Undo -> R.string.undo
                ToolbarAction.Redo -> R.string.redo
            }
        )
        fun actionFromDrag(event: android.view.DragEvent): ToolbarAction? {
            val id = event.clipData?.getItemAt(0)?.text?.toString() ?: return null
            return ToolbarAction.entries.firstOrNull { it.id == id }
        }

        fun acceptDrop(event: android.view.DragEvent) =
            event.clipDescription?.hasMimeType("text/plain") == true

        fun dropListener(onDrop: (ToolbarAction) -> Unit) = View.OnDragListener { view, event ->
            if (!acceptDrop(event)) return@OnDragListener false
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_ENTERED -> view.alpha = 0.65f
                android.view.DragEvent.ACTION_DRAG_EXITED,
                android.view.DragEvent.ACTION_DRAG_ENDED -> view.alpha = 1f
                android.view.DragEvent.ACTION_DROP -> actionFromDrag(event)?.let(onDrop)
            }
            true
        }

        fun startDrag(row: View, action: ToolbarAction): Boolean {
            val data = ClipData.newPlainText("toolbar-action", action.id)
            @Suppress("DEPRECATION")
            return row.startDrag(data, View.DragShadowBuilder(row), action.id, 0)
        }

        fun render() {
            currentContainer.removeAllViews()
            availableContainer.removeAllViews()
            TextView(context).apply { setText(R.string.toolbar_current_actions) }
                .also { currentContainer.addView(it) }
            TextView(context).apply { setText(R.string.toolbar_available_actions) }
                .also { availableContainer.addView(it) }

            availableContainer.setOnDragListener(dropListener { action ->
                actions = actions - action
                render()
            })

            fun insertionZone(index: Int): View = View(context).apply {
                minimumHeight = context.dp(12)
                setOnDragListener(dropListener { dropped ->
                    actions = if (dropped in actions) {
                        ToolbarAction.moveTo(actions, dropped, index)
                    } else {
                        ToolbarAction.insertAt(actions, dropped, index)
                    }
                    render()
                })
            }

            actions.forEachIndexed { index, action ->
                currentContainer.addView(insertionZone(index))
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                ToolButton(context, action.icon, theme).apply {
                    isClickable = false
                    isFocusable = false
                }.also { row.addView(it, LinearLayout.LayoutParams(context.dp(48), context.dp(48))) }
                val labelView = TextView(context).apply {
                    text = label(action)
                }
                row.addView(labelView, LinearLayout.LayoutParams(0, context.dp(48), 1f))
                Button(context).apply {
                    text = "−"
                    contentDescription = "Remove ${label(action)}"
                    setOnClickListener { actions = actions - action; render() }
                }.also { row.addView(it, LinearLayout.LayoutParams(context.dp(56), context.dp(48))) }
                row.setOnLongClickListener { startDrag(row, action) }
                currentContainer.addView(row)
            }
            currentContainer.addView(insertionZone(actions.size))

            ToolbarAction.All.filterNot { it in actions }.forEach { action ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                ToolButton(context, action.icon, theme).apply {
                    isClickable = false
                    isFocusable = false
                }.also { row.addView(it, LinearLayout.LayoutParams(context.dp(48), context.dp(48))) }
                val labelView = TextView(context).apply {
                    text = label(action)
                }
                row.addView(labelView, LinearLayout.LayoutParams(0, context.dp(48), 1f))
                Button(context).apply {
                    text = "+"
                    contentDescription = "Add ${label(action)}"
                    setOnClickListener { actions = ToolbarAction.append(actions, action); render() }
                }.also { row.addView(it, LinearLayout.LayoutParams(context.dp(56), context.dp(48))) }
                row.setOnLongClickListener { startDrag(row, action) }
                availableContainer.addView(row)
            }
            footer.removeAllViews()
            Button(context).apply {
                text = context.getString(R.string.restore_default)
                setOnClickListener { actions = ToolbarAction.Default; render() }
            }.also { footer.addView(it) }
        }
        render()
        service.showDialog(dialog)
    }

    private fun activateAction(action: Action) {
        fcitx.launchOnReady {
            it.activateAction(action.id)
        }
    }

    var popupMenu: PopupMenu? = null

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                when (entry) {
                    is StatusAreaEntry.Fcitx -> {
                        val actions = entry.action.menu
                        if (actions.isNullOrEmpty()) {
                            activateAction(entry.action)
                            return
                        }
                        val popup = PopupMenu(context, view)
                        val menu = popup.menu
                        val hasDivider =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !DeviceUtil.isHMOS && !DeviceUtil.isHonorMagicOS) {
                                menu.setGroupDividerEnabled(true)
                                true
                            } else {
                                false
                            }
                        var groupId = 0 // Menu.NONE; ungrouped
                        actions.forEach {
                            if (it.isSeparator) {
                                if (hasDivider) {
                                    groupId++
                                } else {
                                    val dividerString = buildSpannedString {
                                        color(context.styledColor(android.R.attr.colorForeground).alpha(0.4f)) {
                                            append("──────────")
                                        }
                                    }
                                    menu.add(groupId, 0, 0, dividerString).apply {
                                        isEnabled = false
                                    }
                                }
                            } else {
                                menu.add(groupId, 0, 0, it.shortText).apply {
                                    setOnMenuItemClickListener { _ ->
                                        activateAction(it)
                                        true
                                    }
                                }
                            }
                        }
                        popupMenu?.dismiss()
                        popupMenu = popup
                        popup.show()
                    }
                    is StatusAreaEntry.Android -> when (entry.type) {
                        InputMethod -> fcitx.runImmediately { inputMethodEntryCached }.let {
                            AppUtil.launchMainToInputMethodConfig(
                                context, it.uniqueName, it.displayName
                            )
                        }
                        ReloadConfig -> fcitx.launchOnReady { f ->
                            f.reloadConfig()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                SubtypeManager.syncWith(f.enabledIme())
                            }
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                        Keyboard -> AppUtil.launchMainToKeyboard(context)
                        ThemeList -> AppUtil.launchMainToThemeList(context)
                        ToolbarCollapse -> toolbarControls?.toggle()
                        ToolbarCustomize -> showToolbarEditor()
                    }
                }
            }

            override val theme = this@StatusAreaWindow.theme
        }
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    val view by lazy {
        context.recyclerView {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            layoutManager = gridLayoutManager(4)
            adapter = this@StatusAreaWindow.adapter
        }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        val toolbarEntries = toolbarEntry()?.let { arrayOf<StatusAreaEntry>(it) } ?: emptyArray()
        adapter.entries = arrayOf(
            *toolbarEntries,
            *staticEntries,
            *Array(actions.size) { StatusAreaEntry.fromAction(actions[it]) }
        )
    }

    override fun onCreateView() = view

    private val editorInfoButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_info_24, theme).apply {
            contentDescription = context.getString(R.string.editor_info_inspector)
            setOnClickListener { windowManager.attachWindow(EditorInfoWindow()) }
        }
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.open_input_method_settings)
            setOnClickListener { AppUtil.launchMain(context) }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            if (editorInfoInspector) {
                add(editorInfoButton, lParams(dp(40), dp(40)))
            }
            add(settingsButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateBarExtension() = barExtension

    override fun onAttached() {
        fcitx.launchOnReady {
            val data = it.statusArea()
            service.lifecycleScope.launch {
                onStatusAreaUpdate(data)
            }
        }
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }
}
