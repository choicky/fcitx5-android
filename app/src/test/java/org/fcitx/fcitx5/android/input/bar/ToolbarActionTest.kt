/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.input.bar.ui.ToolbarEditorItemBounds
import org.fcitx.fcitx5.android.input.bar.ui.toolbarEditorInsertionIndex
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
        assertEquals(ToolbarAction.All.toSet(), (state.current + state.available).toSet())
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

    @Test fun editorSessionWritesOnlyWhenExplicitlyCommitted() {
        val session = ToolbarAction.EditorSession(
            ToolbarAction.editorState(listOf(ToolbarAction.Emoji, ToolbarAction.Voice))
        )
        var writes = 0
        session.update(ToolbarAction.editorDefaultState())
        assertEquals(0, writes)
        session.restoreDefault()
        assertEquals(0, writes)
        assertEquals(true, session.commit { writes++ })
        assertEquals(1, writes)
        assertEquals(false, session.commit { writes++ })
        assertEquals(1, writes)
    }

    @Test fun discardedEditorSessionNeverWrites() {
        val session = ToolbarAction.EditorSession(ToolbarAction.editorDefaultState())
        var writes = 0
        session.update(ToolbarAction.editorState(emptyList()))
        session.discard()
        assertEquals(false, session.commit { writes++ })
        assertEquals(0, writes)
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

    @Test fun wrappedAvailableInsertionUsesVisualRows() {
        val bounds = listOf(
            ToolbarEditorItemBounds(0, 0, 72, 76),
            ToolbarEditorItemBounds(80, 0, 152, 76),
            ToolbarEditorItemBounds(160, 0, 232, 76),
            ToolbarEditorItemBounds(0, 84, 72, 160),
            ToolbarEditorItemBounds(80, 84, 152, 160),
        )
        assertEquals(0, toolbarEditorInsertionIndex(-1, 20, bounds))
        assertEquals(1, toolbarEditorInsertionIndex(75, 20, bounds))
        assertEquals(3, toolbarEditorInsertionIndex(250, 20, bounds))
        assertEquals(3, toolbarEditorInsertionIndex(200, 80, bounds))
        assertEquals(4, toolbarEditorInsertionIndex(75, 110, bounds))
        assertEquals(5, toolbarEditorInsertionIndex(160, 140, bounds))
    }

    @Test fun wrappedAvailableInsertionSupportsCrossContainerAndReorder() {
        val state = ToolbarAction.editorState(listOf(ToolbarAction.Emoji, ToolbarAction.Voice))
        val available = listOf(
            ToolbarEditorItemBounds(0, 0, 72, 76),
            ToolbarEditorItemBounds(80, 0, 152, 76),
        )
        val afterSecond = toolbarEditorInsertionIndex(160, 20, available)
        assertEquals(2, afterSecond)
        val reordered = ToolbarAction.editorMoveAvailable(
            state,
            state.available[0],
            afterSecond,
        )
        assertEquals(
            listOf(state.available[1], state.available[0]),
            reordered.available.take(2),
        )

        val crossContainerPosition = toolbarEditorInsertionIndex(0, 20, available)
        val inserted = ToolbarAction.editorInsertAvailable(
            ToolbarAction.editorInsertCurrent(state, ToolbarAction.TextEditing, 2),
            ToolbarAction.TextEditing,
            crossContainerPosition,
        )
        assertEquals(false, ToolbarAction.TextEditing in inserted.current)
        assertEquals(ToolbarAction.TextEditing, inserted.available[crossContainerPosition])
    }

}
