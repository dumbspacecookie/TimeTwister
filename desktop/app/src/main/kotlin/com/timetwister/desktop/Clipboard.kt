package com.timetwister.desktop

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/** Thin wrapper around AWT's system clipboard. */
object Clipboard {
    private val board = Toolkit.getDefaultToolkit().systemClipboard

    fun readText(): String? {
        val t: Transferable = board.getContents(null) ?: return null
        return if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            t.getTransferData(DataFlavor.stringFlavor) as? String
        } else null
    }

    fun writeText(s: String) {
        board.setContents(StringSelection(s), null)
    }
}
