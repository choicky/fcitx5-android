/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.VoicePanelState
import splitties.dimensions.dp
import kotlin.math.max

/**
 * Voice session panel shown over the keyboard keys (the toolbar stays). It only renders the
 * shared session state and the optional microphone level; it never touches AudioRecord.
 * Status text sits at the top, away from a finger holding Space.
 */
@SuppressLint("ViewConstructor")
internal class VoicePanelUi(
    ctx: Context,
    private val theme: Theme,
    onCancel: () -> Unit,
    onFinish: () -> Unit
) : FrameLayout(ctx) {

    private val status = TextView(ctx).apply {
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(theme.keyTextColor)
    }

    private val meter = VoiceLevelView(ctx, theme.accentKeyBackgroundColor)

    // without level data (System ASR) the panel shows a static microphone instead of a meter
    private val micIcon = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_baseline_keyboard_voice_24)
        imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
    }

    private val cancelButton = button(R.string.voice_panel_cancel, theme.keyBackgroundColor, theme.keyTextColor, onCancel)
    private val finishButton = button(R.string.voice_panel_finish, theme.accentKeyBackgroundColor, theme.accentKeyTextColor, onFinish)

    private var levelSeen = false

    init {
        background = theme.backgroundDrawable(keyBorder = true)
        // swallow touches meant for the keys underneath; an ongoing Space touch is unaffected
        isClickable = true
        visibility = GONE
        val meterBox = FrameLayout(ctx).apply {
            addView(meter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(micIcon, LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(cancelButton, LinearLayout.LayoutParams(dp(112), dp(44)).apply { marginEnd = dp(16) })
            addView(finishButton, LinearLayout.LayoutParams(dp(112), dp(44)))
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(meterBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(56)).apply {
                topMargin = dp(12)
            })
            addView(buttons, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dp(20)
            leftMargin = dp(16)
            rightMargin = dp(16)
        })
    }

    fun render(state: VoicePanelState) {
        if (state == VoicePanelState.Hidden) {
            visibility = GONE
            return
        }
        if (visibility != VISIBLE) {
            resetLevel()
            visibility = VISIBLE
        }
        status.setText(
            when (state) {
                VoicePanelState.Listening -> R.string.voice_hint_listening
                VoicePanelState.ReleaseToFinish -> R.string.voice_hint_release_to_finish
                VoicePanelState.ReleaseToCancel -> R.string.voice_hint_release_to_cancel
                VoicePanelState.Recognizing, VoicePanelState.Hidden -> R.string.voice_hint_processing
            }
        )
        status.setTextColor(
            if (state == VoicePanelState.ReleaseToCancel) theme.accentKeyBackgroundColor
            else theme.keyTextColor
        )
        cancelButton.visibility = if (state.showsCancel) VISIBLE else GONE
        finishButton.visibility = if (state.showsFinish) VISIBLE else GONE
    }

    fun pushLevel(level: Float) {
        if (visibility != VISIBLE) return
        if (!levelSeen) {
            levelSeen = true
            meter.visibility = VISIBLE
            micIcon.visibility = GONE
        }
        meter.push(level)
    }

    private fun resetLevel() {
        levelSeen = false
        meter.reset()
        meter.visibility = INVISIBLE
        micIcon.visibility = VISIBLE
    }

    private fun button(text: Int, bg: Int, fg: Int, onClick: () -> Unit) = TextView(context).apply {
        setText(text)
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(fg)
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(8f)
        }
        setOnClickListener { onClick() }
    }
}

/** Bars of the recent, smoothed microphone levels; newest on the right. */
@SuppressLint("ViewConstructor")
internal class VoiceLevelView(ctx: Context, color: Int) : View(ctx) {

    private val smoother = LevelSmoother()
    private val history = FloatArray(BARS)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val minBarHeight = dp(3f)

    fun push(level: Float) {
        history[head] = smoother.update(level)
        head = (head + 1) % BARS
        invalidate()
    }

    fun reset() {
        smoother.reset()
        history.fill(0f)
        head = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val slot = width.toFloat() / BARS
        val barWidth = slot * 0.5f
        for (i in 0 until BARS) {
            val level = history[(head + i) % BARS]
            val barHeight = max(minBarHeight, level * height)
            val left = i * slot + (slot - barWidth) / 2
            val top = (height - barHeight) / 2
            canvas.drawRoundRect(left, top, left + barWidth, top + barHeight, barWidth / 2, barWidth / 2, paint)
        }
    }

    companion object {
        private const val BARS = 32
    }
}
