/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import org.fcitx.fcitx5.android.input.bar.ToolbarAction

/**
 * Configurable Toolbar content that has been requested for presentation but may not be
 * attachable yet.
 *
 * A render requested before the toolbar container has a width cannot decide which actions fit,
 * so the request has to survive until a usable measurement instead of being dropped and left
 * to a later reconciliation event: the first IME presentation renders exactly there, while the
 * freshly constructed bar is still unmeasured.
 */
internal class ToolbarRenderState {

    var actions: List<ToolbarAction> = emptyList()
        private set

    /** [actions] has not been attached because no usable width was available yet. */
    var pending: Boolean = false
        private set

    fun request(actions: List<ToolbarAction>) {
        this.actions = ToolbarAction.normalize(actions)
        pending = true
    }

    /** Whether a layout at [width] (previously [previousWidth]) has to render: unattached content, or a new width. */
    fun needsRender(width: Int, previousWidth: Int): Boolean =
        pending || width != previousWidth

    /**
     * Take the pending request for a container of [width].
     * @return false, keeping the request pending, when [width] cannot be presented in.
     */
    fun renderForWidth(width: Int): Boolean {
        if (width <= 0) return false
        pending = false
        return true
    }
}
