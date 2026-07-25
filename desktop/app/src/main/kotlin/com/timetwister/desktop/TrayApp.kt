package com.timetwister.desktop

import com.timetwister.core.TimeConverter
import com.timetwister.core.TimeParser
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * AWT system-tray host. Same "splice in place" UX as Android's PROCESS_TEXT
 * activity and the iOS Action Extension, adapted to the desktop primitive that
 * actually exists: the clipboard. User copies a phrase containing a time
 * reference, clicks the tray icon → "Convert clipboard", paste — the clipboard
 * now holds the multi-TZ-stamped version.
 *
 * Pure AWT/Swing — no Compose Desktop dependency, no native helpers, no
 * platform-specific code. Runs anywhere a JVM does.
 */
class TrayApp(private val config: UserConfig) {

    fun start() {
        if (!SystemTray.isSupported()) {
            JOptionPane.showMessageDialog(
                null,
                "System tray is not supported on this OS — falling back to settings window.",
                "TimeTwister",
                JOptionPane.WARNING_MESSAGE,
            )
            SettingsWindow(config).open()
            return
        }

        val tray = SystemTray.getSystemTray()
        val icon = TrayIcon(makeIcon(), "TimeTwister")
        icon.isImageAutoSize = true

        val menu = PopupMenu()
        MenuItem("Convert clipboard").apply {
            addActionListener { convertClipboard(icon) }
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
        icon.addActionListener { convertClipboard(icon) }

        tray.add(icon)
    }

    private fun convertClipboard(icon: TrayIcon) {
        val input = Clipboard.readText()
        if (input.isNullOrBlank()) {
            icon.displayMessage("TimeTwister", "Clipboard is empty.", TrayIcon.MessageType.INFO)
            return
        }
        val detected = TimeParser.detectLast(input)
        if (detected == null) {
            icon.displayMessage(
                "TimeTwister",
                "No time reference found in clipboard.",
                TrayIcon.MessageType.INFO,
            )
            return
        }
        val out = TimeConverter.splice(input, config.targetZones())
        Clipboard.writeText(out)
        icon.displayMessage(
            "TimeTwister",
            "Converted — paste to insert.",
            TrayIcon.MessageType.INFO,
        )
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
        // Toolkit indirection lets us cache against the native image cache if needed.
        Toolkit.getDefaultToolkit().createImage(img.source)
        return img
    }

    companion object {
        fun runOnEdt(action: () -> Unit) = SwingUtilities.invokeLater(action)
    }
}
