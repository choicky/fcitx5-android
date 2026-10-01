/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import android.view.View
import org.fcitx.fcitx5.android.input.bar.ui.ToolbarEditorUi
import org.fcitx.fcitx5.android.input.wm.InputWindow

/** Character-area body for the toolbar's transient inline editor session. */
class ToolbarEditorWindow(
    private val editorUi: ToolbarEditorUi,
) : InputWindow.SimpleInputWindow<ToolbarEditorWindow>() {

    override fun onCreateView(): View = editorUi.bodyRoot

    override fun onAttached() = Unit

    override fun onDetached() = Unit
}
