/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexboxLayout.LayoutParams
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import splitties.dimensions.dp

/** Presentation-only view pair for the inline toolbar editor. */
class ToolbarEditorUi(
    private val ctx: Context,
    private val theme: Theme,
    initialState: ToolbarAction.EditorState,
    private val onStateChanged: (ToolbarAction.EditorState) -> Unit,
    private val onRestore: () -> Unit,
    private val onCancel: () -> Unit,
    private val onOk: () -> Unit,
) {
    private var state = initialState

    private val currentContainer = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        minimumWidth = ctx.dp(1)
    }

    private val currentScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        setOnDragListener(dropListener { event, x, y ->
            dropIntoCurrent(event, x + scrollX, y)
        })
        addView(currentContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(40)
        ))
    }

    val currentRoot: View = currentScroll

    private val availableContainer = FlexboxLayout(ctx).apply {
        flexDirection = FlexDirection.ROW
        flexWrap = FlexWrap.WRAP
        alignItems = AlignItems.FLEX_START
        justifyContent = JustifyContent.FLEX_START
        setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(2))
        setOnDragListener(dropListener { event, x, y -> dropIntoAvailable(event, x, y) })
    }

    val bodyRoot: View = ScrollView(ctx).apply {
        isFillViewport = true
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(6), ctx.dp(16), ctx.dp(6))
            addView(TextView(ctx).apply {
                setText(R.string.toolbar_available_actions)
                setTextAppearance(android.R.style.TextAppearance_Material_Small)
                setTextColor(theme.altKeyTextColor)
                contentDescription = ctx.getString(R.string.toolbar_available_actions)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(availableContainer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                    text = ctx.getString(R.string.restore_default)
                    textSize = 12f
                    isAllCaps = false
                    minHeight = ctx.dp(48)
                    minimumHeight = ctx.dp(48)
                    setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
                    setOnClickListener { onRestore() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(ctx).apply {
                    text = ctx.getString(android.R.string.cancel)
                    setOnClickListener { onCancel() }
                })
                addView(Button(ctx).apply {
                    text = ctx.getString(android.R.string.ok)
                    setOnClickListener { onOk() }
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    init {
        render(resetCurrentScroll = true)
    }

    fun setState(newState: ToolbarAction.EditorState, resetCurrentScroll: Boolean = false) {
        state = newState
        render(resetCurrentScroll = resetCurrentScroll)
    }

    private fun update(newState: ToolbarAction.EditorState, revealCurrentEnd: Boolean = false) {
        state = newState
        render(revealCurrentEnd = revealCurrentEnd)
        onStateChanged(newState)
    }

    private fun actionLabel(action: ToolbarAction): String = ctx.getString(
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

    private fun badge(symbol: String, action: ToolbarAction, add: Boolean, onClick: () -> Unit) =
        FrameLayout(ctx).apply {
            contentDescription = ctx.getString(
                if (add) R.string.toolbar_add_action else R.string.toolbar_remove_action,
                actionLabel(action)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(ctx).apply {
                text = symbol
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(theme.genericActiveForegroundColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.genericActiveBackgroundColor)
                }
            }, FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14), Gravity.TOP or Gravity.END).apply {
                topMargin = ctx.dp(4)
                marginEnd = ctx.dp(6)
            })
        }

    private fun icon(action: ToolbarAction): ImageView = ImageView(ctx).apply {
        imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        setImageResource(action.icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun actionBody(action: ToolbarAction, add: Boolean, size: Int): View =
        FrameLayout(ctx).apply {
            contentDescription = actionLabel(action)
            isLongClickable = true
            setOnLongClickListener { startDrag(this, action) }
            if (add) {
                setOnClickListener {
                    update(ToolbarAction.editorAppendCurrent(state, action), revealCurrentEnd = true)
                }
            }
            addView(icon(action).apply {
                if (!add) setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            }, FrameLayout.LayoutParams(size, size,
                if (add) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            ))
            addView(
                badge(if (add) "+" else "−", action, add) {
                    if (add) update(
                        ToolbarAction.editorAppendCurrent(state, action), revealCurrentEnd = true
                    )
                    else update(ToolbarAction.editorAppendAvailable(state, action))
                },
                FrameLayout.LayoutParams(ctx.dp(32), ctx.dp(32), Gravity.TOP or Gravity.END)
            )
        }

    private fun currentBody(action: ToolbarAction): View = actionBody(action, false, ctx.dp(40))

    private fun availableBody(action: ToolbarAction): View = actionBody(action, true, ctx.dp(48)).also {
        val label = TextView(ctx).apply {
            text = actionLabel(action)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        (it as FrameLayout).addView(label, FrameLayout.LayoutParams(
            ctx.dp(68), ctx.dp(24), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ))
        it.contentDescription = actionLabel(action)
        it.layoutParams = LayoutParams(ctx.dp(72), ctx.dp(68)).apply {
            setMargins(ctx.dp(4), ctx.dp(2), ctx.dp(4), ctx.dp(2))
        }
    }

    private fun render(resetCurrentScroll: Boolean = false, revealCurrentEnd: Boolean = false) {
        currentContainer.removeAllViews()
        availableContainer.removeAllViews()
        currentContainer.layoutParams = currentContainer.layoutParams.apply {
            width = if (state.current.isEmpty()) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        state.current.forEach { action ->
            currentContainer.addView(currentBody(action), LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(40)))
        }
        state.available.forEach { action ->
            availableContainer.addView(availableBody(action))
        }
        if (resetCurrentScroll || revealCurrentEnd) {
            currentScroll.post {
                if (resetCurrentScroll) currentScroll.scrollTo(0, 0)
                else currentScroll.fullScroll(View.FOCUS_RIGHT)
            }
        }
    }

    private fun dragAction(event: DragEvent): ToolbarAction? {
        val id = event.clipData?.getItemAt(0)?.text?.toString() ?: return null
        return ToolbarAction.entries.firstOrNull { it.id == id }
    }

    private fun acceptsDrag(event: DragEvent): Boolean =
        event.clipDescription?.hasMimeType("text/plain") == true

    private fun dropListener(onDrop: (DragEvent, Float, Float) -> Unit) = View.OnDragListener { view, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> acceptsDrag(event)
            DragEvent.ACTION_DRAG_ENTERED -> {
                view.alpha = 0.85f
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> true
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                view.alpha = 1f
                true
            }
            DragEvent.ACTION_DROP -> {
                if (dragAction(event) == null) false
                else {
                    onDrop(event, event.x, event.y)
                    true
                }
            }
            else -> false
        }
    }

    private fun startDrag(source: View, action: ToolbarAction): Boolean {
        val data = ClipData.newPlainText("toolbar-action", action.id)
        val shadow = View.DragShadowBuilder(source)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            source.startDragAndDrop(data, shadow, action.id, 0)
        } else {
            @Suppress("DEPRECATION")
            source.startDrag(data, shadow, action.id, 0)
        }
    }

    private fun dropIntoCurrent(event: DragEvent, x: Float, y: Float) {
        val action = dragAction(event) ?: return
        val position = (0 until currentContainer.childCount).firstOrNull { index ->
            x < currentContainer.getChildAt(index).left + currentContainer.getChildAt(index).width / 2f
        } ?: currentContainer.childCount
        val next = if (action in state.current) {
            ToolbarAction.editorMoveCurrent(state, action, position)
        } else {
            ToolbarAction.editorInsertCurrent(state, action, position)
        }
        update(next)
    }

    private fun dropIntoAvailable(event: DragEvent, x: Float, y: Float) {
        val action = dragAction(event) ?: return
        val bounds = (0 until availableContainer.childCount).map { index ->
            availableContainer.getChildAt(index).let { child ->
                ToolbarEditorItemBounds(child.left, child.top, child.right, child.bottom)
            }
        }
        val position = toolbarEditorInsertionIndex(x.toInt(), y.toInt(), bounds)
        val next = if (action in state.available) {
            ToolbarAction.editorMoveAvailable(state, action, position)
        } else {
            ToolbarAction.editorInsertAvailable(state, action, position)
        }
        update(next)
    }
}
