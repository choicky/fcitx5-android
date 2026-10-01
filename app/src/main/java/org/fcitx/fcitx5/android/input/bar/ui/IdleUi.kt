/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.Space
import android.widget.ViewAnimator
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import org.fcitx.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.ClipboardSuggestionUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.InlineSuggestionsUi
import org.fcitx.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.voice.VoiceInputSession
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.matchParent
import splitties.views.imageResource
import timber.log.Timber

class IdleUi(
    override val ctx: Context,
    private val theme: Theme,
    private val popup: PopupComponent,
    private val commonKeyActionListener: CommonKeyActionListener
) : Ui {

    enum class State {
        Empty, Toolbar, Clipboard, NumberRow, InlineSuggestion
    }

    var currentState = State.Empty
        private set

    private var voiceInputButton = false
    private var voiceInputState = VoiceInputSession.State.Idle
    private var inPrivate = false
    private var toolbarEditView: View? = null
    private var toolbarEditing = false

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private val translateDirection by lazy {
        if (ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1f else -1f
    }

    val toolsButton = ToolButton(ctx, R.drawable.ic_baseline_widgets_24, theme).apply {
        contentDescription = ctx.getString(R.string.toolbar_tools)
    }

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24, theme).apply {
        contentDescription = ctx.getString(R.string.hide_keyboard)
    }

    val emptyBar = Space(ctx)

    val buttonsUi = ButtonsBarUi(ctx, theme)
    private val toolbarActions = AppPrefs.getInstance().internal.toolbarActions

    @Keep
    private val toolbarActionsListener =
        org.fcitx.fcitx5.android.data.prefs.ManagedPreference.OnChangeListener<String> { _, value ->
            renderToolbar(ToolbarAction.decode(value))
        }

    val clipboardUi = ClipboardSuggestionUi(ctx, theme)

    val numberRow = NumberRow(ctx, theme).apply {
        visibility = View.GONE
    }

    val inlineSuggestionsBar = InlineSuggestionsUi(ctx)

    private val animator = ViewAnimator(ctx).apply {
        renderToolbar(ToolbarAction.decode(toolbarActions.getValue()))
        add(emptyBar, lParams(matchParent, matchParent))
        add(buttonsUi.root, lParams(matchParent, matchParent))
        add(clipboardUi.root, lParams(matchParent, matchParent))
        add(inlineSuggestionsBar.root, lParams(matchParent, matchParent))
    }

    private val inAnimation by lazy {
        AnimationSet(true).apply {
            duration = 200L
            addAnimation(AlphaAnimation(0f, 1f))
            // 2 stands for Animation.RELATIVE_TO_PARENT
            addAnimation(TranslateAnimation(2, -0.3f * translateDirection, 2, 0f, 0, 0f, 0, 0f))
        }
    }

    private val outAnimation by lazy {
        AnimationSet(true).apply {
            duration = 200L
            addAnimation(AlphaAnimation(1f, 0f))
            addAnimation(TranslateAnimation(2, 0f, 2, -0.3f * translateDirection, 0, 0f, 0, 0f))
        }
    }

    private val idleBody = constraintLayout {
        val size = dp(KawaiiBarComponent.HEIGHT)
        add(toolsButton, lParams(size, size) {
            startOfParent()
            centerVertically()
        })
        add(hideKeyboardButton, lParams(size, size) {
            endOfParent()
            centerVertically()
        })
        add(animator, lParams(matchConstraints, matchParent) {
            after(toolsButton)
            before(hideKeyboardButton)
            centerVertically()
        })
    }

    override val root = frameLayout {
        add(idleBody, lParams(matchParent, matchParent))
        add(numberRow, lParams(matchParent, matchParent))
    }

    init {
        toolbarActions.registerOnChangeListener(toolbarActionsListener)
    }

    fun privateMode(activate: Boolean = true) {
        inPrivate = activate
        toolsButton.contentDescription = ctx.getString(
            if (inPrivate) R.string.private_mode else R.string.toolbar_tools
        )
    }

    fun setVoiceInputButton(isVoiceInput: Boolean, callback: View.OnClickListener) {
        voiceInputButton = isVoiceInput
        if (isVoiceInput) updateVoiceInputButton()
        buttonsUi.voiceInputButton.setOnClickListener(callback)
        renderToolbar(ToolbarAction.decode(toolbarActions.getValue()))
    }

    private fun renderToolbar(actions: List<ToolbarAction>) {
        if (toolbarEditing) return
        buttonsUi.render(actions.filter { it != ToolbarAction.Voice || voiceInputButton })
    }

    fun enterToolbarEdit(editor: ToolbarEditorUi) {
        if (toolbarEditing) return
        toolbarEditing = true
        val editView = editor.currentRoot
        toolbarEditView = editView
        animator.addView(
            editView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        animator.displayedChild = animator.indexOfChild(editView)
        toolsButton.isEnabled = false
        hideKeyboardButton.isEnabled = false
        toolsButton.alpha = 0.55f
        hideKeyboardButton.alpha = 0.55f
    }

    fun exitToolbarEdit() {
        if (!toolbarEditing) return
        toolbarEditing = false
        toolbarEditView?.let {
            animator.removeView(it)
            toolbarEditView = null
        }
        toolsButton.isEnabled = true
        hideKeyboardButton.isEnabled = true
        toolsButton.alpha = 1f
        hideKeyboardButton.alpha = 1f
        renderToolbar(ToolbarAction.decode(toolbarActions.getValue()))
        animator.displayedChild = when (currentState) {
            State.Empty -> 0
            State.Toolbar -> 1
            State.Clipboard -> 2
            State.InlineSuggestion -> 3
            State.NumberRow -> 0
        }
    }

    internal fun setVoiceInputState(state: VoiceInputSession.State) {
        voiceInputState = state
        if (voiceInputButton) updateVoiceInputButton()
    }

    private fun updateVoiceInputButton() {
        val active = voiceInputState != VoiceInputSession.State.Idle
        // stopped and waiting for the final result: keep the stop icon, dimmed
        val processing = voiceInputState == VoiceInputSession.State.Stopping
        buttonsUi.voiceInputButton.setIcon(
            if (active) R.drawable.ic_baseline_stop_24
            else R.drawable.ic_baseline_keyboard_voice_24
        )
        buttonsUi.voiceInputButton.alpha = if (processing) 0.4f else 1f
        buttonsUi.voiceInputButton.contentDescription = ctx.getString(
            when {
                processing -> R.string.voice_hint_processing
                active -> R.string.stop_voice_input
                else -> R.string.start_voice_input
            }
        )
    }

    private fun clearAnimation() {
        animator.inAnimation = null
        animator.outAnimation = null
    }

    private fun setAnimation() {
        animator.inAnimation = inAnimation
        animator.outAnimation = outAnimation
    }

    private fun enableSlideTransition(inTarget: View, outTarget: View, inGravity: Int, outGravity: Int) {
        val slideIn = Slide(inGravity).apply { duration = 200L }
        val slideOut = Slide(outGravity).apply { duration = 200L }
        slideIn.addTarget(inTarget)
        slideOut.addTarget(outTarget)
        val set = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(slideIn)
            addTransition(slideOut)
        }
        TransitionManager.beginDelayedTransition(root, set)
    }

    fun updateState(state: State, fromUser: Boolean = false) {
        Timber.d("Switch idle ui to $state")
        if (
            !fromUser ||
            disableAnimation ||
            (state == State.InlineSuggestion || currentState == State.InlineSuggestion) ||
            (state == State.NumberRow || currentState == State.NumberRow)
        ) {
            clearAnimation()
        } else {
            setAnimation()
        }
        when (state) {
            State.Empty -> animator.displayedChild = 0
            State.Toolbar -> animator.displayedChild = 1
            State.Clipboard -> animator.displayedChild = 2
            State.NumberRow -> {}
            State.InlineSuggestion -> animator.displayedChild = 3
        }
        if (state == State.NumberRow) {
            numberRow.keyActionListener = commonKeyActionListener.listener
            numberRow.popupActionListener = popup.listener
            if (fromUser && !disableAnimation) {
                enableSlideTransition(numberRow, idleBody, Gravity.END, Gravity.START)
            }
            numberRow.visibility = View.VISIBLE
            idleBody.visibility = View.GONE
        } else if (currentState == State.NumberRow) {
            if (fromUser && !disableAnimation) {
                enableSlideTransition(idleBody, numberRow, Gravity.START, Gravity.END)
            }
            idleBody.visibility = View.VISIBLE
            numberRow.visibility = View.GONE
            numberRow.keyActionListener = null
            numberRow.popupActionListener = null
            popup.dismissAll()
        }
        currentState = state
    }
}
