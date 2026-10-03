/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    private var configuredActions = emptyList<ToolbarAction>()

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val size = ctx.dp(40)
        it.layoutParams = FlexboxLayout.LayoutParams(size, size)
    }

    val undoButton = toolButton(R.drawable.ic_baseline_undo_24).apply {
        contentDescription = ctx.getString(R.string.undo)
    }

    val redoButton = toolButton(R.drawable.ic_baseline_redo_24).apply {
        contentDescription = ctx.getString(R.string.redo)
    }

    val cursorMoveButton = toolButton(R.drawable.ic_cursor_move).apply {
        contentDescription = ctx.getString(R.string.text_editing)
    }

    val clipboardButton = toolButton(R.drawable.ic_clipboard).apply {
        contentDescription = ctx.getString(R.string.clipboard)
    }

    val quickPhraseButton = toolButton(R.drawable.ic_baseline_emoji_objects_24).apply {
        contentDescription = ctx.getString(R.string.quickphrase)
    }
    val emojiButton = toolButton(R.drawable.ic_baseline_tag_faces_24).apply {
        contentDescription = ctx.getString(R.string.emoji_and_symbols)
    }
    private val buttons = mapOf(
        ToolbarAction.Emoji to emojiButton, ToolbarAction.QuickPhrase to quickPhraseButton,
        ToolbarAction.Clipboard to clipboardButton,
        ToolbarAction.TextEditing to cursorMoveButton, ToolbarAction.Undo to undoButton,
        ToolbarAction.Redo to redoButton,
    )

    fun render(actions: List<ToolbarAction>) {
        configuredActions = ToolbarAction.normalize(actions)
        renderMeasured()
    }

    private fun renderMeasured() {
        if (root.width <= 0) return
        root.removeAllViews()
        val result = ToolbarAction.presentationActions(configuredActions) { actions ->
            val availableWidth = root.width - root.paddingLeft - root.paddingRight
            val requiredWidth = actions.sumOf { action ->
                val button = buttons[action] ?: return@sumOf 0
                val params = button.layoutParams
                val margins = (params as? ViewGroup.MarginLayoutParams)?.let {
                    it.leftMargin + it.rightMargin
                } ?: 0
                val width = params?.width?.takeIf { it > 0 } ?: button.measuredWidth
                width + margins
            }
            requiredWidth <= availableWidth
        }
        val actions = (result as? ToolbarAction.PresentationResult.Fits)?.actions ?: return
        actions.forEach { action ->
            buttons[action]?.let(root::addView)
        }
    }

    init {
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) renderMeasured()
        }
    }

}
