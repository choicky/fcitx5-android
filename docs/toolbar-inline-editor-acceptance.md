# Toolbar Inline Editor V2

The Toolbar Editor is now an inline Edit Mode entered from `Tools → Edit toolbar`.
The previous AlertDialog editor is superseded.

- The real Toolbar remains in its normal physical position.
- Tools and Hide remain fixed, visible, disabled, and outside `ToolbarAction.EditorState`.
- Edit Mode displays a temporary working copy of Current in the real Toolbar.
- Only explicit `OK` encodes and persists Current to `toolbarActions`.
- Cancel, Android Back, input-context changes, input-view teardown, and view replacement discard the working copy.
- Restore Default changes only the working copy; `OK` is still required to persist it.
- Edit Mode exposes the complete Current in a horizontal viewport and does not apply normal right-end suppression.
- Normal mode continues to use presentation-only right-end suppression in `ButtonsBarUi`.
- Available Actions are a wrapping Flexbox action palette in the character-area InputWindow.
- The palette and Current use separate presentation surfaces; no generic Toolbar/Management UI framework is introduced.
- Available ordering is session-local and is never persisted.

The accepted physical-device gates are explicit: verify Android Back and all input-view teardown paths discard without a preference write, and verify real long-press drag/drop for Current↔Current, Available↔Current, Current↔Available, Available↔Available, and zero-Current insertion. Pure `ToolbarAction` tests do not prove Android touch dispatch or `DragEvent` delivery.

The temporary Edit Mode preview partially supersedes the earlier statement that the real Toolbar has no live preview: a working-copy preview is allowed during Edit Mode, while persistent live updates remain prohibited and OK-only persistence remains required.
