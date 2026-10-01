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
        assertEquals(ToolbarAction.Default, ToolbarAction.withVoice(hidden, true))
        assertEquals(
            listOf(ToolbarAction.Emoji, ToolbarAction.QuickPhrase, ToolbarAction.Voice,
                ToolbarAction.TextEditing),
            ToolbarAction.withVoice(
                listOf(ToolbarAction.Emoji, ToolbarAction.QuickPhrase, ToolbarAction.TextEditing),
                true
            )
        )
        assertEquals(hidden, ToolbarAction.withVoice(hidden, false))
    }

    @Test fun toolbarEditorPlusAppendsRight() = assertEquals(
        listOf(ToolbarAction.Emoji, ToolbarAction.TextEditing, ToolbarAction.Voice),
        ToolbarAction.append(listOf(ToolbarAction.Emoji, ToolbarAction.TextEditing), ToolbarAction.Voice)
    )

    @Test fun toolbarDropInsertsAtExplicitPosition() = assertEquals(
        listOf(ToolbarAction.Emoji, ToolbarAction.Voice, ToolbarAction.TextEditing),
        ToolbarAction.insertAt(
            listOf(ToolbarAction.Emoji, ToolbarAction.TextEditing), ToolbarAction.Voice, 1
        )
    )

    @Test fun toolbarDragReordersWithoutChangingMembership() = assertEquals(
        listOf(ToolbarAction.Voice, ToolbarAction.Emoji, ToolbarAction.TextEditing),
        ToolbarAction.moveTo(
            listOf(ToolbarAction.Emoji, ToolbarAction.Voice, ToolbarAction.TextEditing),
            ToolbarAction.Voice, 0
        )
    )

    @Test fun narrowToolbarSuppressesConfiguredSuffixAndKeepsConfiguredState() {
        val actions = ToolbarAction.Default
        assertEquals(
            ToolbarAction.PresentationResult.Fits(
                listOf(ToolbarAction.Emoji, ToolbarAction.QuickPhrase, ToolbarAction.Voice,
                    ToolbarAction.Clipboard)
            ), ToolbarAction.presentationActions(actions) { it.size <= 4 }
        )
        assertEquals(
            ToolbarAction.PresentationResult.Fits(
                listOf(ToolbarAction.Emoji, ToolbarAction.QuickPhrase, ToolbarAction.Voice)
            ), ToolbarAction.presentationActions(actions) { it.size <= 3 }
        )
        assertEquals(
            ToolbarAction.PresentationResult.Fits(
                listOf(ToolbarAction.Emoji, ToolbarAction.QuickPhrase)
            ), ToolbarAction.presentationActions(actions) { it.size <= 2 }
        )
        assertEquals(
            ToolbarAction.PresentationResult.Fits(listOf(ToolbarAction.Emoji)),
            ToolbarAction.presentationActions(actions) { it.size <= 1 }
        )
        assertEquals(
            ToolbarAction.PresentationResult.Fits(emptyList()),
            ToolbarAction.presentationActions(actions) { it.isEmpty() }
        )
        assertEquals(actions, ToolbarAction.Default)
    }

    @Test fun arbitraryOrderSuppressesOnlyConfiguredSuffix() = assertEquals(
        ToolbarAction.PresentationResult.Fits(
            listOf(ToolbarAction.Voice, ToolbarAction.Emoji, ToolbarAction.Undo)
        ), ToolbarAction.presentationActions(
            listOf(ToolbarAction.Voice, ToolbarAction.Emoji, ToolbarAction.Undo,
                ToolbarAction.Redo)
        ) { it.size <= 3 }
    )

    @Test fun editorStatePartitionsAllActionsWithoutDuplicates() {
        val state = ToolbarAction.editorState(listOf(ToolbarAction.Voice, ToolbarAction.Emoji))
        assertEquals(listOf(ToolbarAction.Voice, ToolbarAction.Emoji), state.current)
        assertEquals(ToolbarAction.All.filterNot { it in state.current }, state.available)
        assertEquals(ToolbarAction.All, (state.current + state.available).distinct())
    }

    @Test fun zeroAndAllActionsAreValidEditorConfigurations() {
        assertEquals(emptyList<ToolbarAction>(), ToolbarAction.decode(ToolbarAction.encode(emptyList())))
        val all = ToolbarAction.editorState(ToolbarAction.All)
        assertEquals(ToolbarAction.All, all.current)
        assertEquals(emptyList<ToolbarAction>(), all.available)
    }

    @Test fun editorOperationsUseAppendAndExplicitPositions() {
        val initial = ToolbarAction.editorState(listOf(ToolbarAction.Emoji, ToolbarAction.Voice))
        val added = ToolbarAction.editorAppendCurrent(initial, ToolbarAction.TextEditing)
        assertEquals(
            listOf(ToolbarAction.Emoji, ToolbarAction.Voice, ToolbarAction.TextEditing),
            added.current
        )
        val inserted = ToolbarAction.editorInsertCurrent(added, ToolbarAction.Clipboard, 1)
        assertEquals(
            listOf(ToolbarAction.Emoji, ToolbarAction.Clipboard, ToolbarAction.Voice,
                ToolbarAction.TextEditing), inserted.current
        )
        val moved = ToolbarAction.editorInsertAvailable(inserted, ToolbarAction.Voice, 0)
        assertEquals(listOf(ToolbarAction.Emoji, ToolbarAction.Clipboard, ToolbarAction.TextEditing), moved.current)
        assertEquals(ToolbarAction.Voice, moved.available.first())
    }

    @Test fun editorRestoreDefaultAndAvailableReorderAreTransactionalHelpers() {
        val restored = ToolbarAction.editorDefaultState()
        assertEquals(ToolbarAction.Default, restored.current)
        assertEquals(listOf(ToolbarAction.Undo, ToolbarAction.Redo), restored.available)
        val reordered = ToolbarAction.editorMoveAvailable(restored, ToolbarAction.Redo, 0)
        assertEquals(listOf(ToolbarAction.Redo, ToolbarAction.Undo), reordered.available)
        assertEquals(ToolbarAction.Default, restored.current)
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

}
