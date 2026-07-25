package com.timetwister.desktop

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard as AwtClipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/**
 * Thin, defensive wrapper around AWT's system clipboard.
 *
 * The system clipboard is a *shared OS resource*, not a local buffer, so every
 * access here is a call that can legitimately fail through no fault of ours:
 *
 *  - Windows serialises clipboard access behind a global lock. Any other process
 *    holding it (Excel, RDP, a clipboard manager, an installer) makes AWT throw
 *    `IllegalStateException: cannot open system clipboard`. This is routine, not
 *    exceptional, and it clears on its own within milliseconds.
 *  - `IOException` ("data is no longer available in the requested format") happens
 *    when the owning process dies between the flavour check and the read.
 *  - `UnsupportedFlavorException` is a genuine race: `isDataFlavorSupported` and
 *    `getTransferData` are two separate round-trips to the owner, and the clipboard
 *    contents can change in between.
 *
 * All of the above are thrown on the EDT from the tray listener, where an uncaught
 * throw means a stack trace on a console nobody is looking at and a user who sees
 * literally nothing happen. Hence: every operation returns a [Result] so callers are
 * forced to decide what to tell the user, and reads are retried a few times because
 * the dominant failure mode (lock contention) is transient by nature.
 */
object Clipboard {

    /** Attempts per read, and the pause between them. Two retries covers a typical
     *  clipboard-manager hold without adding perceptible latency (max ~100 ms). */
    private const val READ_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 50L

    /**
     * Resolved lazily rather than at object-init. `Toolkit.getSystemClipboard()`
     * throws `HeadlessException` in a headless JVM; doing it in an initialiser would
     * turn that into an `ExceptionInInitializerError` on first touch of *any* member,
     * which is a much worse diagnostic than a failed [Result].
     */
    private val board: Result<AwtClipboard> by lazy {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard }
    }

    /**
     * Reads the clipboard as text.
     *
     * `success(null)` means "read fine, but there is no text on it" (empty clipboard,
     * or an image/file). `failure` means we could not read it at all — those are the
     * only cases worth showing the user an error for.
     */
    fun readText(): Result<String?> {
        val clipboard = board.getOrElse { return Result.failure(it) }

        var last: Throwable? = null
        repeat(READ_ATTEMPTS) { attempt ->
            val attemptResult = runCatching {
                val t: Transferable = clipboard.getContents(null) ?: return Result.success(null)
                if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    t.getTransferData(DataFlavor.stringFlavor) as? String
                } else {
                    null
                }
            }
            attemptResult.onSuccess { return Result.success(it) }
            last = attemptResult.exceptionOrNull()
            if (attempt < READ_ATTEMPTS - 1) pause()
        }
        return Result.failure(last ?: IllegalStateException("Clipboard read failed"))
    }

    /**
     * Writes text to the clipboard. Retried on the same grounds as the read — taking
     * clipboard *ownership* contends for the same OS lock that reading does.
     */
    fun writeText(s: String): Result<Unit> {
        val clipboard = board.getOrElse { return Result.failure(it) }

        var last: Throwable? = null
        repeat(READ_ATTEMPTS) { attempt ->
            val attemptResult = runCatching { clipboard.setContents(StringSelection(s), null) }
            attemptResult.onSuccess { return Result.success(Unit) }
            last = attemptResult.exceptionOrNull()
            if (attempt < READ_ATTEMPTS - 1) pause()
        }
        return Result.failure(last ?: IllegalStateException("Clipboard write failed"))
    }

    /** Backoff between attempts. Interruption is preserved, never swallowed. */
    private fun pause() {
        try {
            Thread.sleep(RETRY_DELAY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
