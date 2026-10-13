/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import org.fcitx.fcitx5.android.input.bar.ToolbarAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the toolbar render handoff around the bar's first measurement: the first IME
 * presentation requests its content while the container is still unmeasured, and must complete
 * from that presentation's own layout rather than from a later one.
 */
class ToolbarRenderStateTest {

    @Test fun requestBeforeFirstMeasurementStaysPendingAndKeepsContent() {
        val state = ToolbarRenderState()
        state.request(ToolbarAction.Default)
        // construction and the first onStartInput both run with root.width == 0
        assertEquals(false, state.renderForWidth(0))
        assertEquals(ToolbarAction.Default, state.actions)
        assertEquals(true, state.pending)
    }

    @Test fun firstPresentationCompletesOnItsOwnFirstUsableLayout() {
        val state = ToolbarRenderState()
        state.request(ToolbarAction.Default)
        state.renderForWidth(0)
        assertEquals(true, state.needsRender(width = 1080, previousWidth = 0))
        assertEquals(true, state.renderForWidth(1080))
        assertEquals(false, state.pending)
        // a repeated layout at the same usable width must not render again
        assertEquals(false, state.needsRender(width = 1080, previousWidth = 1080))
    }

    @Test fun zeroWidthLayoutsKeepRetryingUntilAWidthExists() {
        val state = ToolbarRenderState()
        state.request(ToolbarAction.Default)
        repeat(3) {
            assertEquals(true, state.needsRender(width = 0, previousWidth = 0))
            assertEquals(false, state.renderForWidth(0))
        }
        assertEquals(ToolbarAction.Default, state.actions)
    }

    @Test fun laterPresentationRendersWithoutWaitingForLayout() {
        val state = ToolbarRenderState()
        state.request(ToolbarAction.Default)
        assertEquals(true, state.renderForWidth(1080))
        state.request(ToolbarAction.Default - ToolbarAction.Voice)
        assertEquals(true, state.renderForWidth(1080))
        assertEquals(false, state.pending)
    }

    @Test fun widthChangeReRendersEvenWhenNothingIsPending() {
        val state = ToolbarRenderState()
        state.request(ToolbarAction.Default)
        state.renderForWidth(1080)
        assertEquals(true, state.needsRender(width = 1920, previousWidth = 1080))
    }

    @Test fun deferredRequestKeepsUserConfigurationInsteadOfDefaults() {
        val configured = listOf(
            ToolbarAction.Undo, ToolbarAction.Emoji, ToolbarAction.Voice, ToolbarAction.Redo
        )
        val state = ToolbarRenderState()
        state.request(configured)
        state.renderForWidth(0)
        state.renderForWidth(1080)
        assertEquals(configured, state.actions)
    }

    @Test fun deferredRequestNormalizesDuplicatesWithoutReordering() {
        val state = ToolbarRenderState()
        state.request(listOf(ToolbarAction.Clipboard, ToolbarAction.Emoji, ToolbarAction.Clipboard))
        assertEquals(
            listOf(ToolbarAction.Clipboard, ToolbarAction.Emoji), state.actions
        )
    }

    @Test fun emptyConfigurationIsAFulfilledRenderNotAPendingOne() {
        val state = ToolbarRenderState()
        state.request(emptyList())
        assertEquals(true, state.renderForWidth(1080))
        assertEquals(false, state.pending)
        assertEquals(false, state.needsRender(width = 1080, previousWidth = 1080))
    }

    @Test fun deferralNeverSuppressesActionsByItself() {
        val state = ToolbarAction.normalize(ToolbarAction.Default)
        val rendered = ToolbarRenderState().apply {
            request(state)
            renderForWidth(0)
            renderForWidth(1080)
        }
        // narrow-width suppression is still decided by the measured width alone
        assertEquals(
            listOf(ToolbarAction.Emoji, ToolbarAction.QuickPhrase),
            (ToolbarAction.presentationActions(rendered.actions) { it.size <= 2 }
                    as ToolbarAction.PresentationResult.Fits).actions
        )
        assertEquals(
            ToolbarAction.Default,
            (ToolbarAction.presentationActions(rendered.actions) { true }
                    as ToolbarAction.PresentationResult.Fits).actions
        )
    }
}
