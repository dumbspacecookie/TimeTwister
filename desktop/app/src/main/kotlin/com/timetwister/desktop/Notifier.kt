package com.timetwister.desktop

import java.awt.Component
import java.awt.TrayIcon
import javax.swing.JOptionPane

/**
 * Where user-facing messages go.
 *
 * Exists because the convert flow is reachable from two shells with completely
 * different feedback primitives: the tray icon (balloon notifications) and the
 * settings window (dialogs) — and the settings window is the *only* shell available
 * when the tray is unsupported or registration fails. Without this indirection the
 * fallback path would have no way to tell the user anything, which is precisely the
 * bug the tray-less path had.
 */
fun interface Notifier {
    fun notify(title: String, message: String, error: Boolean)

    companion object {
        /** Tray balloon notifications. */
        fun of(icon: TrayIcon): Notifier = Notifier { title, message, error ->
            icon.displayMessage(
                title,
                message,
                if (error) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.INFO,
            )
        }

        /** Modal dialogs, for the window-only fallback. */
        fun of(parent: Component?): Notifier = Notifier { title, message, error ->
            JOptionPane.showMessageDialog(
                parent,
                message,
                title,
                if (error) JOptionPane.ERROR_MESSAGE else JOptionPane.INFORMATION_MESSAGE,
            )
        }
    }
}
