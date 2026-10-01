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

        /** Presentation-only suppression; the final non-fit state remains explicit. */
        fun presentationActions(
            actions: List<ToolbarAction>, fits: (List<ToolbarAction>) -> Boolean
        ): PresentationResult {
            val normalized = normalize(actions)
            if (fits(normalized)) return PresentationResult.Fits(normalized)
            val suppressed = normalized.toMutableList()
            listOf(QuickPhrase, Emoji, TextEditing, Clipboard).forEach { action ->
                if (action in suppressed) suppressed.remove(action)
                if (fits(suppressed)) return PresentationResult.Fits(suppressed)
            }
            return if (fits(suppressed)) {
                PresentationResult.Fits(suppressed)
            } else {
                PresentationResult.StillInsufficient(suppressed)
            }
        }

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
