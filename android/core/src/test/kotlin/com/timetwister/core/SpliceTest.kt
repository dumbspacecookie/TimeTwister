package com.timetwister.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Covers TimeConverter.splice — the pure helper that ProcessTextActivity calls.
 * Tests live in :core so they run on the JVM (no emulator) and the activity
 * stays a thin shell around this function.
 */
class SpliceTest {

    private val ct = ZoneId.of("America/Chicago")
    private val et = ZoneId.of("America/New_York")
    private val pt = ZoneId.of("America/Los_Angeles")
    private val targets = listOf(ct, et, pt)

    // Fixed anchor — same convention as TimeConverterTest. 2026-01-15 is solidly inside EST.
    private val anchor: ZonedDateTime =
        ZonedDateTime.parse("2026-01-15T08:00:00-05:00[America/New_York]")

    @Test fun spliceReplacesDetectedRangeInPlace() {
        val out = TimeConverter.splice("lets do 5pm CT", targets, anchor)
        assertEquals("lets do 5pm CT (6pm ET · 3pm PT)", out)
    }

    @Test fun splicePreservesTrailingText() {
        // Anchor is in EST (Jan), not EDT — so offsets are 1hr earlier than the README's
        // May/DST example. 7pm IST = 13:30 UTC = 8:30 EST / 7:30 CST / 5:30 PST.
        val out = TimeConverter.splice("meet at 7pm IST tomorrow", targets, anchor)
        assertEquals("meet at 7pm IST (7:30am CT · 8:30am ET · 5:30am PT) tomorrow", out)
    }

    @Test fun spliceReturnsInputUnchangedWhenNoDetection() {
        val input = "room 5 is open"
        assertEquals(input, TimeConverter.splice(input, targets, anchor))
    }

    @Test fun spliceReturnsInputUnchangedWhenEmpty() {
        assertEquals("", TimeConverter.splice("", targets, anchor))
    }

    @Test fun spliceOnLastTimeWhenMultiplePresent() {
        // Mirrors the activity behavior: detectLast wins so the user sees the most recent ref.
        val out = TimeConverter.splice("9am CT standup, demo at 2pm PT", targets, anchor)
        assertEquals("9am CT standup, demo at 2pm PT (4pm CT · 5pm ET)", out)
    }

    // --- maybeSplice: the activity-decision wrapper -----------------------------------

    @Test fun maybeSpliceReturnsNullWhenReadOnly() {
        org.junit.Assert.assertNull(
            TimeConverter.maybeSplice("5pm CT", targets, readOnly = true, now = anchor),
        )
    }

    @Test fun maybeSpliceReturnsNullWhenNoDetection() {
        org.junit.Assert.assertNull(
            TimeConverter.maybeSplice("room 5 is open", targets, readOnly = false, now = anchor),
        )
    }

    @Test fun maybeSpliceReturnsSplicedTextOnHit() {
        val out = TimeConverter.maybeSplice("lets do 5pm CT", targets, readOnly = false, now = anchor)
        assertEquals("lets do 5pm CT (6pm ET · 3pm PT)", out)
    }
}
