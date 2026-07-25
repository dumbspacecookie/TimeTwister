package com.timetwister.desktop

import com.timetwister.core.TimeConverter
import com.timetwister.core.TimeZoneAlias
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import java.time.ZoneId

/**
 * Minimal Swing settings window: the user's target zones with add/remove, a live
 * preview of what a stamp will look like, and a Convert button.
 *
 * The Convert button is not a convenience — on any system where the tray is
 * unsupported or tray registration fails, this window is the *only* shell the app
 * has, so without it the product would have no reachable function at all.
 */
class SettingsWindow(private val config: UserConfig) {

    fun open() {
        SwingUtilities.invokeLater { showFrame() }
    }

    private fun showFrame() {
        // Singleton guard. Two frames would each hold an independent DefaultListModel
        // snapshot, and whichever saved last would silently clobber the other's writes
        // to zones.txt. One window, always.
        openFrame?.let {
            it.isVisible = true
            it.state = JFrame.NORMAL
            it.toFront()
            it.requestFocus()
            return
        }

        val frame = JFrame("TimeTwister — Settings")
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.size = Dimension(460, 420)
        frame.setLocationRelativeTo(null)
        openFrame = frame
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) {
                if (openFrame === frame) openFrame = null
            }
        })

        val model = DefaultListModel<ZoneId>()
        config.targetZones().forEach { model.addElement(it) }

        val list = JList(model)
        list.cellRenderer = ZoneListRenderer()

        // Preview pane. Android's settings screen has one and it is the single most
        // useful thing in that UI: without it a desktop user has to round-trip through
        // another app just to find out what their zone list actually produces.
        val preview = JLabel()
        preview.font = preview.font.deriveFont(Font.PLAIN, 13f)
        preview.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)

        fun zonesInModel(): List<ZoneId> = (0 until model.size()).map { model.get(it) }

        fun refreshPreview() {
            // Fixed width so long zone lists wrap instead of stretching the window.
            preview.text = "<html><body style='width:400px'><b>Preview:</b> " +
                escapeHtml(TimeConverter.splice(PREVIEW_SAMPLE, zonesInModel())) +
                "</body></html>"
        }

        /** Saves and reports failure — a silently-lost write is the bug we're avoiding. */
        fun persist() {
            config.setTargetZones(zonesInModel()).onFailure { e ->
                JOptionPane.showMessageDialog(
                    frame,
                    "Couldn't save your zones to\n${UserConfig.defaultPath()}\n\n" +
                        (e.message ?: e::class.java.simpleName),
                    "TimeTwister",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }

        val addBox = JComboBox(commonZonesForPicker.toTypedArray())
        addBox.renderer = ZoneListRenderer()

        val addBtn = JButton("Add")
        addBtn.addActionListener {
            val z = addBox.selectedItem as? ZoneId ?: return@addActionListener
            if (zonesInModel().none { it.id == z.id }) {
                model.addElement(z)
                persist()
                refreshPreview()
            }
        }

        val removeBtn = JButton("Remove selected")
        removeBtn.addActionListener {
            val idx = list.selectedIndex
            if (idx >= 0) {
                model.remove(idx)
                persist()
                refreshPreview()
            }
        }

        // The primary action. Labelled with the accelerator so the shortcut is
        // discoverable; see Hotkey for why that shortcut is window-scoped, not global.
        val convertBtn = JButton("Convert clipboard  (${Hotkey.LABEL})")
        convertBtn.font = convertBtn.font.deriveFont(Font.BOLD)
        convertBtn.toolTipText =
            "Reads the clipboard, stamps the time it finds, and writes it back."
        convertBtn.addActionListener { ConvertAction.run(config, Notifier.of(frame)) }

        Hotkey.install(frame.rootPane) { ConvertAction.run(config, Notifier.of(frame)) }

        val top = JPanel(BorderLayout())
        top.add(JLabel("  Stamps include these zones (in order):"), BorderLayout.NORTH)
        top.add(JScrollPane(list), BorderLayout.CENTER)
        top.add(preview, BorderLayout.SOUTH)

        val zoneControls = JPanel(FlowLayout(FlowLayout.LEFT))
        zoneControls.add(JLabel("Add:"))
        zoneControls.add(addBox)
        zoneControls.add(addBtn)
        zoneControls.add(removeBtn)

        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT))
        actionRow.add(convertBtn)

        val bottom = JPanel()
        bottom.layout = BoxLayout(bottom, BoxLayout.Y_AXIS)
        bottom.add(zoneControls)
        bottom.add(Box.createVerticalStrut(4))
        bottom.add(actionRow)

        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(top, BorderLayout.CENTER)
        frame.contentPane.add(bottom, BorderLayout.SOUTH)

        refreshPreview()
        frame.isVisible = true
    }

    // Curated short list — covers the common ones without dumping ZoneId.getAvailableZoneIds()
    // (~600 entries, terrible UX). User can hand-edit ~/.timetwister/zones.txt for exotics.
    private val commonZonesForPicker: List<ZoneId> = listOf(
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
        "America/Anchorage", "Pacific/Honolulu",
        "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Madrid", "Europe/Rome",
        "Europe/Amsterdam", "Europe/Stockholm", "Europe/Athens", "Europe/Moscow",
        "Asia/Dubai", "Asia/Kolkata", "Asia/Singapore", "Asia/Bangkok",
        "Asia/Hong_Kong", "Asia/Shanghai", "Asia/Tokyo", "Asia/Seoul",
        "Australia/Sydney", "Pacific/Auckland", "America/Sao_Paulo", "Africa/Johannesburg",
    ).map(ZoneId::of)

    private class ZoneListRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): java.awt.Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is ZoneId) text = "${TimeZoneAlias.shortLabel(value)}  —  ${value.id}"
            return c
        }
    }

    companion object {
        /** The single live frame, or null. EDT-confined, so no synchronisation needed. */
        private var openFrame: JFrame? = null

        /** Mirrors the README's example so the preview matches the documented behaviour. */
        private const val PREVIEW_SAMPLE = "lets do 5pm CT"

        /** JLabel renders HTML, so zone output has to be escaped before interpolation. */
        private fun escapeHtml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
