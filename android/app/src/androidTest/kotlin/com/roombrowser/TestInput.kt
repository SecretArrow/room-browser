package com.roombrowser

import androidx.test.uiautomator.UiDevice

/**
 * Empties the focused text field with [backspaces] backspaces.
 *
 * ONE `input` invocation carrying many keycodes — never a `;`-joined list of
 * `input` calls. `/system/bin/input` is a shell script that ends in
 * `exec app_process … com.android.commands.input.Input`, and `exec` REPLACES
 * the shell, so in `input keyevent X; input keyevent X; …` every command
 * after the first is discarded and a "40 backspaces" burst deletes exactly
 * one character. (The device log settles it: one
 * `Calling main entry com.android.commands.input.Input` per burst, not 40.)
 *
 * That stayed harmless for as long as the idiom was only ever used on a field
 * that was already empty. CI 2c9f63d is where it stopped being harmless: a
 * second navigation in the same tab left the previous URL in the omnibox, the
 * burst removed one character of it, and the `input text` that followed
 * APPENDED — so the browser was handed
 * `…/connect-45508http://…/silent-45508`, a URL nothing had typed.
 *
 * This only sends the keys. Whether the field is now empty is NOT answerable
 * in general: it depends on some node exposing the field's text. Where one
 * does, clear and read it back; where none does (the omnibox — the node
 * carrying its contentDescription is the container Box, whose text is the
 * placeholder on an empty field) do not gate on a read-back at all, send a
 * count larger than any value the field can hold, and let the caller's real
 * assertion decide. CI 36932653641 is the second shape taken too far: a
 * "clear, verify, retry" loop around a field that can never read empty.
 */
fun UiDevice.clearFocusedField(backspaces: Int = 40) {
    executeShellCommand("input keyevent " + "KEYCODE_DEL ".repeat(backspaces).trimEnd())
}
