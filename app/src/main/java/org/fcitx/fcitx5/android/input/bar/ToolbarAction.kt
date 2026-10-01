/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

/** The configurable middle section of the keyboard toolbar. */
enum class ToolbarAction(val id: String) {
    Emoji("emoji"), QuickPhrase("quick_phrase"), Voice("voice"), Clipboard("clipboard"),
    TextEditing("text_editing"), Undo("undo"), Redo("redo");

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

        /** Presentation-only suppression; the unresolved post-Emoji state remains explicit. */
        fun presentationActions(
            actions: List<ToolbarAction>, fits: (List<ToolbarAction>) -> Boolean
        ): PresentationResult {
            val normalized = normalize(actions)
            if (fits(normalized)) return PresentationResult.Fits(normalized)
            val suppressed = normalized.toMutableList()
            if (ToolbarAction.QuickPhrase in suppressed) suppressed.remove(ToolbarAction.QuickPhrase)
            if (fits(suppressed)) return PresentationResult.Fits(suppressed)
            if (ToolbarAction.Emoji in suppressed) suppressed.remove(ToolbarAction.Emoji)
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
