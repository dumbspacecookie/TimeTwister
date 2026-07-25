package com.timetwister.desktop

import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * The convert accelerator: **Ctrl+Shift+T**.
 *
 * ## Honest scope: this is NOT an OS-global hotkey
 *
 * The JDK has no API for registering a system-wide hotkey. AWT/Swing key handling is
 * built on the JVM's own focus subsystem — `KeyboardFocusManager`, `InputMap`,
 * `Toolkit.addAWTEventListener` — and every one of those only ever sees events the OS
 * has already routed *to a window this JVM owns*. There is no pure-JDK equivalent of
 * `RegisterHotKey` (Win32), `RegisterEventHotKey` (Carbon/macOS) or an X11 passive
 * grab. A true global hotkey requires native code reached through JNI or JNA, which
 * means a third-party dependency — see `desktop/README.md` for the options and their
 * costs. That decision is the owner's, so it has not been taken here.
 *
 * What this file provides is the best in-JVM approximation:
 *  - the binding works whenever a TimeTwister window has focus ([install]), and
 *  - the tray menu advertises the accelerator so the shortcut is discoverable and the
 *    label is truthful about what it is ([LABEL], [menuShortcut]).
 */
object Hotkey {

    /** Human-readable form, appended to menu/button labels. */
    const val LABEL: String = "Ctrl+Shift+T"

    /** Swing form, for `InputMap` registration on focused windows. */
    val keyStroke: KeyStroke =
        KeyStroke.getKeyStroke(KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK)

    // NB: `MenuItem.setShortcut` is deliberately not used to advertise this on the tray
    // menu. AWT dispatches menu shortcuts through a Frame's MenuBar, which a tray
    // PopupMenu has no part in, so it would never fire; and its rendering is
    // platform-dependent (Windows appends it right-aligned, XAWT and the macOS peer may
    // show nothing). Baking LABEL into the menu item's text instead is visible on every
    // platform and doesn't imply a binding that isn't there.

    /**
     * Binds the accelerator on [component] for the whole window containing it.
     *
     * `WHEN_IN_FOCUSED_WINDOW` rather than `WHEN_FOCUSED` so the shortcut fires no
     * matter which control inside the settings window has focus.
     */
    fun install(component: JComponent, action: () -> Unit) {
        val key = "timetwister.convert"
        component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, key)
        component.actionMap.put(
            key,
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = action()
            },
        )
    }
}
