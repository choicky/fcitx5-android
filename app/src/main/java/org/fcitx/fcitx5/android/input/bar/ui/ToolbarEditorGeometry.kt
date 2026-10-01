/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar.ui

internal data class ToolbarEditorItemBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Maps a point in a wrapped action palette to the visual insertion index. */
internal fun toolbarEditorInsertionIndex(
    x: Int,
    y: Int,
    items: List<ToolbarEditorItemBounds>,
): Int {
    if (items.isEmpty()) return 0

    val rows = items.withIndex().fold(
        mutableListOf<MutableList<IndexedValue<ToolbarEditorItemBounds>>>()
    ) { acc, item ->
        val row = acc.lastOrNull { it.first().value.top == item.value.top }
        if (row == null) acc += mutableListOf(item) else row += item
        acc
    }
    val row = rows.firstOrNull { y < it.first().value.top || y < it.first().value.bottom }
        ?: rows.last()
    if (y < row.first().value.top) return row.first().index
    return row.firstOrNull { x < (it.value.left + it.value.right) / 2 }
        ?.index
        ?: row.last().index + 1
}
