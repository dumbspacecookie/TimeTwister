package com.timetwister.desktop

import java.awt.AWTException
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * AWT system-tray host. Same "splice in place" UX as Android's PROCESS_TEXT
 * activity and the iOS Action Extension, adapted to the desktop primitive that
 * actually exists: the clipboard. User copies a phrase containing a time
 * reference, opens the tray menu → "Convert clipboard", pastes — the clipboard
 * now holds the multi-TZ-stamped version.
 *
 * Pure AWT/Swing — no Compose Desktop dependency, no native helpers, no
 * platform-specific code. Runs anywhere a JVM does.
 *
 * Every step of startup here is allowed to fail on a real machine, so each one
 * degrades to the next-best shell rather than throwing:
 *   headless JVM → clear stderr message and exit
 *   no tray support → settings window (which carries its own Convert button)
 *   tray registration fails → settings window
 */
class TrayApp(private val config: UserConfig) {

    fun start() {
        // Must come first. In a headless JVM every Swing/AWT entry point below throws
        // HeadlessException — including the JOptionPane that used to *report* the
        // problem, and the JFrame behind it. The old code failed twice trying to tell
        // the user it had failed once, and left the JVM alive with nothing on screen.
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println(HEADLESS_MESSAGE)
            return
        }

        if (!SystemTray.isSupported()) {
            // No balloon notifications available here, so the window is the whole app.
            // It has a Convert button precisely for this case.
            SettingsWindow(config).open()
            return
        }

        val tray = SystemTray.getSystemTray()
        val icon = TrayIcon(makeIcon(), "TimeTwister — ${Hotkey.LABEL} to convert (in-window)")
        icon.isImageAutoSize = true
        val notifier = Notifier.of(icon)

        val menu = PopupMenu()
        // The documented primary path. icon.addActionListener (left-click) is NOT
        // reliable: on macOS a left-click on a tray icon opens the popup menu and never
        // fires the action listener, and on Linux behaviour varies by DE. The menu item
        // is the one gesture that works on all three platforms, so it is what the README
        // tells people to use.
        // The accelerator is spelled out in the label rather than set via
        // MenuItem.setShortcut — see Hotkey for why that API is the wrong tool here.
        MenuItem("Convert clipboard  (${Hotkey.LABEL} in window)").apply {
            addActionListener { ConvertAction.run(config, notifier) }
            menu.add(this)
        }
        MenuItem("Settings…").apply {
            addActionListener { SettingsWindow(config).open() }
            menu.add(this)
        }
        menu.addSeparator()
        MenuItem("Quit").apply {
            addActionListener {
                tray.remove(icon)
                Runtime.getRuntime().exit(0)
            }
            menu.add(this)
        }
        icon.popupMenu = menu

        // Kept as a bonus gesture on the platforms where it works (Windows, most Linux
        // DEs) — but never advertised as the way in, for the reasons above.
        icon.addActionListener { ConvertAction.run(config, notifier) }

        try {
            tray.add(icon)
        } catch (e: AWTException) {
            // SystemTray.isSupported() reports what the desktop environment *claims*;
            // registration is where reality bites — GNOME without an AppIndicator
            // extension, a full notification area, or a restarting shell. Uncaught this
            // meant no icon, no window, no message, and a JVM lingering as a zombie.
            System.err.println("TimeTwister: could not register a tray icon (${e.message}); opening settings window instead.")
            SettingsWindow(config).open()
        }
    }

    // Programmatic tray icon. 16×16 dark square with a clock face — keeps the
    // jar resource-free and looks fine on light and dark task bars.
    private fun makeIcon(): BufferedImage {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(40, 40, 40)
        g.fillRoundRect(0, 0, size, size, 4, 4)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 10)
        val fm = g.fontMetrics
        val text = "TT"
        val tx = (size - fm.stringWidth(text)) / 2
        val ty = (size - fm.height) / 2 + fm.ascent
        g.drawString(text, tx, ty)
        g.dispose()
        return img
    }

    companion object {
        /** Shared so [main] can bail before it ever touches the EDT, and [start] can
         *  still guard itself for any other caller. */
        val HEADLESS_MESSAGE: String
            get() = "TimeTwister: this JVM is headless (no display available), so neither " +
                "the tray icon nor the settings window can start.\n" +
                "Run it from a desktop session, or edit your zones directly at:\n  " +
                UserConfig.defaultPath()

        fun runOnEdt(action: () -> Unit) = SwingUtilities.invokeLater(action)
    }
}
