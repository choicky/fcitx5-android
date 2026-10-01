/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

/** The configurable middle section of the keyboard toolbar. */
enum class ToolbarAction(val id: String) {
    Emoji("emoji"), QuickPhrase("quick_phrase"), Voice("voice"), Clipboard("clipboard"),
    TextEditing("text_editing"), Undo("undo"), Redo("redo");

    enum class ToolbarToggle { Collapse, Expand }

    companion object {
        val Default = listOf(Emoji, QuickPhrase, Voice, Clipboard, TextEditing)
        val All = entries.toList()

        fun initialDefault(hasToolbarActions: Boolean, legacyShowVoice: Boolean?): List<ToolbarAction> =
            if (!hasToolbarActions && legacyShowVoice == false) Default - Voice else Default

        fun withVoice(actions: List<ToolbarAction>, visible: Boolean): List<ToolbarAction> =
            if (visible) (actions + Voice).distinct() else actions - Voice

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
