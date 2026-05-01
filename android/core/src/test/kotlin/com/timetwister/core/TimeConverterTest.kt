package com.timetwister.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeConverterTest {

    private val ct = ZoneId.of("America/Chicago")
    private val et = ZoneId.of("America/New_York")
    private val pt = ZoneId.of("America/Los_Angeles")

    // Fixed anchor so tests are deterministic regardless of when they run.
    // 2026-01-15 is solidly within EST (no DST ambiguity).
    private val anchor: ZonedDateTime =
        ZonedDateTime.parse("2026-01-15T08:00:00-05:00[America/New_York]")

    @Test fun parserFindsSimpleTime() {
        val detected = TimeParser.detect("lets do 5pm CT")
        assertEquals(1, detected.size)
        assertEquals(17, detected[0].hour)
        assertEquals(0, detected[0].minute)
        assertEquals(ct.id, detected[0].zone.id)
    }

    @Test fun parserHandlesMinutes() {
        val detected = TimeParser.detect("how about 5:30pm pacific")
        assertEquals(17, detected.first().hour)
        assertEquals(30, detected.first().minute)
        assertEquals(pt.id, detected.first().zone.id)
    }

    @Test fun parserIgnoresBareNumbers() {
        val detected = TimeParser.detect("room 5 is open")
        assertTrue("bare '5' without am/pm or TZ should not match", detected.isEmpty())
    }

    @Test fun parser24Hour() {
        val detected = TimeParser.detect("landing at 17:00 ET")
        assertEquals(17, detected.first().hour)
        assertEquals(0, detected.first().minute)
        assertEquals(et.id, detected.first().zone.id)
    }

    @Test fun parserNoon() {
        val detected = TimeParser.detect("call at noon CT")
        assertEquals(1, detected.size)
        assertEquals(12, detected[0].hour)
        assertEquals(0, detected[0].minute)
        assertEquals(ct.id, detected[0].zone.id)
    }

    @Test fun parserMidnight() {
        val detected = TimeParser.detect("deploy at midnight ET")
        assertEquals(1, detected.size)
        assertEquals(0, detected[0].hour)
        assertEquals(0, detected[0].minute)
        assertEquals(et.id, detected[0].zone.id)
    }

    @Test fun parserNoonWithoutTzStillMatches() {
        // noon/midnight self-disambiguate — no TZ required.
        val detected = TimeParser.detect("see you at noon")
        assertEquals(1, detected.size)
        assertEquals(12, detected[0].hour)
        assertFalse(detected[0].hadExplicitZone)
    }

    @Test fun converterStampAcrossZones() {
        val detected = DetectedTime(
            hour = 17, minute = 0, zone = ct,
            hadExplicitZone = true,
            range = 0..5, originalText = "5pm CT",
        )
        val stamp = TimeConverter.renderStamp(detected, listOf(ct, et, pt), now = anchor)
        assertTrue(stamp, stamp.contains("5pm CT"))
        assertTrue(stamp, stamp.contains("6pm ET"))
        assertTrue(stamp, stamp.contains("3pm PT"))
    }

    @Test fun converterOmitsSourceFromTargets() {
        val detected = DetectedTime(
            hour = 17, minute = 0, zone = ct,
            hadExplicitZone = true,
            range = 0..5, originalText = "5pm CT",
        )
        val stamp = TimeConverter.renderStamp(detected, listOf(ct), now = anchor)
        assertFalse("no parens when targets == [source]", stamp.contains("("))
    }
}
