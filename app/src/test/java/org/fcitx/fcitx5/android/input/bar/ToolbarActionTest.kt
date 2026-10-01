/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarActionTest {
    @Test fun defaultOrderIsStable() = assertEquals(
        listOf(ToolbarAction.Emoji, ToolbarAction.QuickPhrase, ToolbarAction.Voice,
            ToolbarAction.Clipboard, ToolbarAction.TextEditing), ToolbarAction.Default
    )

    @Test fun cleanInstallDefaultIncludesVoice() = assertEquals(
        ToolbarAction.Default, ToolbarAction.initialDefault(false, null)
    )

    @Test fun legacyVoiceOffMigratesOnlyBeforeToolbarPreferenceExists() {
        assertEquals(
            ToolbarAction.Default - ToolbarAction.Voice,
            ToolbarAction.initialDefault(false, false)
        )
        assertEquals(
            ToolbarAction.Default,
            ToolbarAction.initialDefault(true, false)
        )
    }

    @Test fun voiceSettingsToggleChangesOnlyToolbarMembership() {
        val hidden = ToolbarAction.withVoice(ToolbarAction.Default, false)
        assertEquals(hidden + ToolbarAction.Voice, ToolbarAction.withVoice(hidden, true))
        assertEquals(hidden, ToolbarAction.withVoice(hidden, false))
    }

    @Test fun toolbarToggleMapsToActionAndDirection() {
        assertEquals(ToolbarAction.ToolbarToggle.Collapse, ToolbarAction.toolbarToggle(true))
        assertEquals(ToolbarAction.ToolbarToggle.Expand, ToolbarAction.toolbarToggle(false))
    }

    @Test fun hiddenAndUnknownActionsAreNotRendered() = assertEquals(
        listOf(ToolbarAction.Emoji, ToolbarAction.TextEditing),
        ToolbarAction.decode("emoji,unknown,text_editing,emoji")
    )

    @Test fun persistenceRoundTripPreservesReorder() {
        val reordered = listOf(ToolbarAction.Undo, ToolbarAction.Emoji, ToolbarAction.Voice)
        assertEquals(reordered, ToolbarAction.decode(ToolbarAction.encode(reordered)))
    }

    @Test fun invalidStoredValueRestoresDefault() = assertEquals(
        ToolbarAction.Default, ToolbarAction.decode("unknown")
    )

    @Test fun fixedEdgesAreNotPartOfActionPool() {
        assertEquals(ToolbarAction.All, ToolbarAction.Default + listOf(ToolbarAction.Undo, ToolbarAction.Redo))
    }

    @Test fun editorOrderShowsEnabledActionsBeforeHiddenActions() = assertEquals(
        listOf(ToolbarAction.TextEditing, ToolbarAction.Emoji, ToolbarAction.QuickPhrase,
            ToolbarAction.Voice, ToolbarAction.Clipboard, ToolbarAction.Undo, ToolbarAction.Redo),
        ToolbarAction.editorOrder(listOf(ToolbarAction.TextEditing, ToolbarAction.Emoji,
            ToolbarAction.QuickPhrase, ToolbarAction.Voice, ToolbarAction.Clipboard))
    )

    @Test fun reenabledActionIsAppendedToEnabledOrder() {
        val current = listOf(ToolbarAction.Emoji, ToolbarAction.TextEditing)
        val reenabled = (current + ToolbarAction.Voice).distinct()
        assertEquals(
            listOf(ToolbarAction.Emoji, ToolbarAction.TextEditing, ToolbarAction.Voice,
                ToolbarAction.QuickPhrase, ToolbarAction.Clipboard, ToolbarAction.Undo, ToolbarAction.Redo),
            ToolbarAction.editorOrder(reenabled)
        )
    }
}
