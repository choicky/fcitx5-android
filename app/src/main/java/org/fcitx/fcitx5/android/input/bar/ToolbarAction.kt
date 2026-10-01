/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

/** The configurable middle section of the keyboard toolbar. */
enum class ToolbarAction(val id: String) {
    Emoji("emoji"), QuickPhrase("quick_phrase"), Voice("voice"), Clipboard("clipboard"),
    TextEditing("text_editing"), Undo("undo"), Redo("redo");

    companion object {
        val Default = listOf(Emoji, QuickPhrase, Voice, Clipboard, TextEditing)
        val All = entries.toList()
        fun decode(raw: String, default: List<ToolbarAction> = Default): List<ToolbarAction> {
            val decoded = raw.split(',').mapNotNull { value -> entries.firstOrNull { it.id == value.trim() } }.distinct()
            return if (decoded.isEmpty() && raw.isNotEmpty()) default else decoded
        }
        fun encode(actions: List<ToolbarAction>) = actions.joinToString(",") { it.id }
        fun normalize(actions: List<ToolbarAction>) = actions.distinct().filter { it in All }
    }
}
