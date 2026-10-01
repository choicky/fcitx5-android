/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R

/** The configurable middle section of the keyboard toolbar. */
enum class ToolbarAction(val id: String, @DrawableRes val icon: Int) {
    Emoji("emoji", R.drawable.ic_baseline_tag_faces_24),
    QuickPhrase("quick_phrase", R.drawable.ic_baseline_emoji_objects_24),
    Voice("voice", R.drawable.ic_baseline_keyboard_voice_24),
    Clipboard("clipboard", R.drawable.ic_clipboard),
    TextEditing("text_editing", R.drawable.ic_cursor_move),
    Undo("undo", R.drawable.ic_baseline_undo_24),
    Redo("redo", R.drawable.ic_baseline_redo_24);

    enum class ToolbarToggle { Collapse, Expand }

    data class EditorState(
        val current: List<ToolbarAction>,
        val available: List<ToolbarAction>
    )

    sealed class PresentationResult {
        data class Fits(val actions: List<ToolbarAction>) : PresentationResult()
        data class StillInsufficient(val actions: List<ToolbarAction>) : PresentationResult()
    }

    companion object {
        val Default = listOf(Emoji, QuickPhrase, Voice, Clipboard, TextEditing)
        val All = entries.toList()

        fun initialDefault(hasToolbarActions: Boolean, legacyShowVoice: Boolean?): List<ToolbarAction> =
            if (!hasToolbarActions && legacyShowVoice == false) Default - Voice else Default

        /** Settings-specific Voice re-enable: restore its default relative position. */
        fun withVoice(actions: List<ToolbarAction>, visible: Boolean): List<ToolbarAction> {
            val normalized = normalize(actions)
            if (!visible) return normalized - Voice
            if (Voice in normalized) return normalized
            val voiceIndex = Default.indexOf(Voice)
            val insertAt = normalized.indexOfFirst { action ->
                Default.indexOf(action).takeIf { it >= 0 }?.let { it > voiceIndex } == true
            }.takeIf { it >= 0 } ?: normalized.size
            return normalized.toMutableList().apply { add(insertAt, Voice) }
        }

        /** Toolbar Editor `+` semantics: add an action at the right end. */
        fun append(actions: List<ToolbarAction>, action: ToolbarAction): List<ToolbarAction> =
            (normalize(actions) + action).distinct()

        /** Add an action at an explicit Toolbar drop position. */
        fun insertAt(
            actions: List<ToolbarAction>, action: ToolbarAction, position: Int
        ): List<ToolbarAction> = normalize(actions).filterNot { it == action }.toMutableList().apply {
            add(position.coerceIn(0, size), action)
        }

        /** Move an already configured action before the item at [position]. */
        fun moveTo(
            actions: List<ToolbarAction>, action: ToolbarAction, position: Int
        ): List<ToolbarAction> {
            val normalized = normalize(actions)
            val oldPosition = normalized.indexOf(action)
            val adjusted = if (oldPosition >= 0 && oldPosition < position) position - 1 else position
            return insertAt(normalized, action, adjusted)
        }

        /** Presentation-only suppression from the right end of configured order. */
        fun presentationActions(
            actions: List<ToolbarAction>, fits: (List<ToolbarAction>) -> Boolean
        ): PresentationResult {
            val normalized = normalize(actions)
            for (size in normalized.size downTo 0) {
                val prefix = normalized.take(size)
                if (fits(prefix)) return PresentationResult.Fits(prefix)
            }
            // An empty configurable prefix is a valid presentation; Tools and Hide remain.
            return PresentationResult.Fits(emptyList())
        }

        /** Build the transactional Editor state from Current and a session-local Available order. */
        fun editorState(
            current: List<ToolbarAction>,
            availableOrder: List<ToolbarAction> = All
        ): EditorState {
            val currentNormalized = normalize(current)
            val availableNormalized = normalize(availableOrder).filterNot { it in currentNormalized }
            val missing = All.filterNot { it in currentNormalized || it in availableNormalized }
            return EditorState(currentNormalized, availableNormalized + missing)
        }

        fun editorAppendCurrent(state: EditorState, action: ToolbarAction) =
            editorState(append(state.current, action), state.available)

        fun editorInsertCurrent(state: EditorState, action: ToolbarAction, position: Int) =
            editorState(insertAt(state.current, action, position), state.available)

        fun editorMoveCurrent(state: EditorState, action: ToolbarAction, position: Int) =
            editorState(moveTo(state.current, action, position), state.available)

        fun editorInsertAvailable(state: EditorState, action: ToolbarAction, position: Int) =
            editorState(state.current - action, state.available.toMutableList().apply {
                add(position.coerceIn(0, size), action)
            })

        fun editorAppendAvailable(state: EditorState, action: ToolbarAction) =
            editorInsertAvailable(state, action, state.available.size)

        fun editorMoveAvailable(state: EditorState, action: ToolbarAction, position: Int) =
            editorState(state.current, moveTo(state.available, action, position))

        fun editorDefaultState() = editorState(Default, listOf(Undo, Redo))

        fun toolbarToggle(isExpanded: Boolean) =
            if (isExpanded) ToolbarToggle.Collapse else ToolbarToggle.Expand

        fun decode(raw: String, default: List<ToolbarAction> = Default): List<ToolbarAction> {
            val decoded = raw.split(',').mapNotNull { value -> entries.firstOrNull { it.id == value.trim() } }.distinct()
            return if (decoded.isEmpty() && raw.isNotEmpty()) default else decoded
        }
        fun encode(actions: List<ToolbarAction>) = actions.joinToString(",") { it.id }
        fun normalize(actions: List<ToolbarAction>) = actions.distinct().filter { it in All }

        /** Enabled actions keep their saved order; hidden actions follow in stable pool order. */
        fun editorOrder(actions: List<ToolbarAction>): List<ToolbarAction> {
            val enabled = normalize(actions)
            return enabled + All.filterNot { it in enabled }
        }
    }
}
