package com.timetwister.desktop

import com.timetwister.core.TimeZoneAlias
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import java.time.ZoneId

/**
 * Minimal Swing settings window. Lists user's target zones with add/remove,
 * persists changes via UserConfig immediately. No live preview pane — the
 * tray menu's "Convert clipboard" exercises the full flow.
 */
class SettingsWindow(private val config: UserConfig) {

    fun open() {
        SwingUtilities.invokeLater { showFrame() }
    }

    private fun showFrame() {
        val frame = JFrame("TimeTwister — Settings")
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.size = Dimension(420, 320)
        frame.setLocationRelativeTo(null)

        val model = DefaultListModel<ZoneId>()
        config.targetZones().forEach { model.addElement(it) }

        val list = JList(model)
        list.cellRenderer = ZoneListRenderer()

        val addBox = JComboBox(commonZonesForPicker.toTypedArray())
        addBox.renderer = ZoneListRenderer()

        val addBtn = JButton("Add")
        addBtn.addActionListener {
            val z = addBox.selectedItem as? ZoneId ?: return@addActionListener
            if ((0 until model.size()).none { model.get(it).id == z.id }) {
                model.addElement(z)
                config.setTargetZones((0 until model.size()).map { model.get(it) })
            }
        }

        val removeBtn = JButton("Remove selected")
        removeBtn.addActionListener {
            val idx = list.selectedIndex
            if (idx >= 0) {
                model.remove(idx)
                config.setTargetZones((0 until model.size()).map { model.get(it) })
            }
        }

        val top = JPanel(BorderLayout())
        top.add(JLabel("  Stamps include these zones (in order):"), BorderLayout.NORTH)
        top.add(JScrollPane(list), BorderLayout.CENTER)

        val bottom = JPanel(FlowLayout(FlowLayout.LEFT))
        bottom.add(JLabel("Add:"))
        bottom.add(addBox)
        bottom.add(addBtn)
        bottom.add(removeBtn)

        frame.contentPane.layout = BorderLayout()
        frame.contentPane.add(top, BorderLayout.CENTER)
        frame.contentPane.add(bottom, BorderLayout.SOUTH)
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
}
