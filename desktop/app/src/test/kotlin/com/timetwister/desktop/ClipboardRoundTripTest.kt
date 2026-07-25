package com.timetwister.desktop

import com.timetwister.core.TimeConverter
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * End-to-end check on the desktop convert flow:
 *   clipboard.read → TimeConverter.splice → clipboard.write
 *
 * This test touches the **real system clipboard** — there is no injectable seam, and
 * faking one would stop it covering the thing it exists to cover (that AWT clipboard
 * access actually works end to end). Three consequences are handled explicitly:
 *
 *  - It saves and restores whatever the developer had copied. Silently destroying the
 *    contents of someone's clipboard because they ran `gradlew test` is not acceptable
 *    collateral for a unit test.
 *  - It *skips* (Assume) rather than returns when no clipboard is available. A bare
 *    `return` records a green pass for a test that never ran, which is worse than no
 *    test at all because it hides the gap.
 *  - Environment problems are turned into skips one call at a time, never with a blanket
 *    `catch (Throwable)` around the assertion — that would silently downgrade a genuine
 *    regression into a skip.
 */
class ClipboardRoundTripTest {

    private val targets = listOf("America/Chicago", "America/New_York", "America/Los_Angeles").map(ZoneId::of)
    private val anchor: ZonedDateTime =
        ZonedDateTime.parse("2026-01-15T08:00:00-05:00[America/New_York]")

    @Test fun clipboardConvertsAndPreservesSurroundingText() {
        assumeFalse("headless JVM — no AWT clipboard", GraphicsEnvironment.isHeadless())

        // Snapshot first. A failed read means the clipboard is unusable in this
        // environment, so skip rather than proceed to trample it.
        val saved = Clipboard.readText()
        assumeAvailable("read the clipboard", saved)

        try {
            assumeAvailable("write to the clipboard", Clipboard.writeText("ping at 5pm CT please"))

            val read = Clipboard.readText()
            assumeAvailable("read back from the clipboard", read)
            val input = read.getOrNull().orEmpty()

            val out = TimeConverter.splice(input, targets, anchor)
            assumeAvailable("write the converted text", Clipboard.writeText(out))

            val final = Clipboard.readText()
            assumeAvailable("read the converted text", final)

            // Past this point every failure is a real one and must be reported as such.
            assertEquals("ping at 5pm CT (6pm ET · 3pm PT) please", final.getOrNull())
        } finally {
            // Restore. Null means the clipboard held something non-textual (or nothing);
            // we cannot faithfully put that back, so leave our text rather than blank it
            // out and pretend we restored it.
            saved.getOrNull()?.let { Clipboard.writeText(it) }
        }
    }

    /** Some CI environments expose AWT but block clipboard access; skip honestly there. */
    private fun assumeAvailable(what: String, result: Result<*>) {
        assumeTrue(
            "could not $what in this environment: ${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
    }
}
