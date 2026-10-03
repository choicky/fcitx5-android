package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarActionTest {
    @Test fun normalizeRemovesDuplicates() {
        assertEquals(
            listOf(ToolbarAction.Emoji, ToolbarAction.Clipboard),
            ToolbarAction.normalize(listOf(ToolbarAction.Emoji, ToolbarAction.Emoji, ToolbarAction.Clipboard))
        )
    }

    @Test fun editorCommitPersistsOnlyOnCommit() {
        var saved: ToolbarAction.EditorState? = null
        val session = ToolbarAction.EditorSession(ToolbarAction.editorState(ToolbarAction.Default))
        session.commit { saved = it }
        assertEquals(ToolbarAction.Default, saved?.current)
    }

    @Test fun movingActionPreservesOrder() {
        assertEquals(
            listOf(ToolbarAction.TextEditing, ToolbarAction.Emoji, ToolbarAction.Clipboard),
            ToolbarAction.moveTo(
                listOf(ToolbarAction.Emoji, ToolbarAction.Clipboard, ToolbarAction.TextEditing),
                ToolbarAction.TextEditing, 0
            )
        )
    }
}
