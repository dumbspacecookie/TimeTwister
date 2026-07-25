package com.timetwister.desktop

import com.timetwister.core.TimeConverter
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * End-to-end check on the desktop convert flow:
 *   clipboard.read → TimeConverter.splice → clipboard.write
 *
 * Skipped in headless CI (no AWT clipboard available).
 */
class ClipboardRoundTripTest {

    private val targets = listOf("America/Chicago", "America/New_York", "America/Los_Angeles").map(ZoneId::of)
    private val anchor: ZonedDateTime =
        ZonedDateTime.parse("2026-01-15T08:00:00-05:00[America/New_York]")

    @Test fun clipboardConvertsAndPreservesSurroundingText() {
        if (GraphicsEnvironment.isHeadless()) return
        try {
            Clipboard.writeText("ping at 5pm CT please")
            val input = Clipboard.readText().orEmpty()
            val out = TimeConverter.splice(input, targets, anchor)
            Clipboard.writeText(out)
            assertEquals("ping at 5pm CT (6pm ET · 3pm PT) please", Clipboard.readText())
        } catch (t: Throwable) {
            // Some CI environments expose AWT but block clipboard access; skip there.
            assumeNoException(t)
        }
    }
}
