package com.timetwister.desktop

import com.timetwister.core.TimeConverter
import com.timetwister.core.TimeParser

/**
 * The one and only conversion flow: read clipboard → detect a time → splice the
 * multi-zone stamp in place → write it back → tell the user what happened.
 *
 * Lives outside [TrayApp] deliberately. It is the product's entire function, and it
 * must be invokable from every shell that can exist at runtime — the tray menu, the
 * settings window's Convert button (the only entry point on a tray-less system), and
 * the in-window accelerator. Keeping it in TrayApp is what made it unreachable in the
 * fallback path.
 */
object ConvertAction {

    /** Longest converted string we will inline into a notification body. Balloon
     *  notifications are truncated by the OS anyway, and a wall of text is worse than
     *  an ellipsis. */
    private const val PREVIEW_LIMIT = 160

    fun run(config: UserConfig, notify: Notifier) {
        val read = Clipboard.readText()

        val input = read.getOrElse { e ->
            notify.notify(
                "TimeTwister",
                "Couldn't read the clipboard — another app may be holding it. " +
                    "Try again in a moment.\n(${describe(e)})",
                true,
            )
            return
        }

        if (input.isNullOrBlank()) {
            notify.notify("TimeTwister", "Clipboard is empty.", false)
            return
        }

        if (TimeParser.detectLast(input) == null) {
            notify.notify("TimeTwister", "No time reference found in clipboard.", false)
            return
        }

        val out = TimeConverter.splice(input, config.targetZones())

        Clipboard.writeText(out).onFailure { e ->
            notify.notify(
                "TimeTwister",
                "Converted, but couldn't write it back to the clipboard — " +
                    "another app may be holding it. Try again in a moment.\n(${describe(e)})",
                true,
            )
            return
        }

        // Show the result, not just "done". The user pastes into another app; if the
        // stamp is wrong they should find out here rather than after sending it.
        notify.notify("TimeTwister — paste to insert", truncate(out), false)
    }

    private fun truncate(s: String): String =
        if (s.length <= PREVIEW_LIMIT) s else s.take(PREVIEW_LIMIT - 1) + "…"

    /** Exception messages from AWT are terse but genuinely useful here ("cannot open
     *  system clipboard" tells the user to close whatever is hogging it). */
    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
}
