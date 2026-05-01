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

    // --- coverage hardening (regressions caught by StampDemo) ---

    @Test fun parserLowercaseTzToken() {
        // regex is case-insensitive; "utc" lowercase should resolve to UTC.
        val detected = TimeParser.detect("ship by 11pm utc")
        assertEquals(23, detected.first().hour)
        assertEquals("UTC", detected.first().zone.id)
    }

    @Test fun parserUppercaseAmPm() {
        val detected = TimeParser.detect("call at 5PM CT")
        assertEquals(17, detected.first().hour)
        assertEquals(ct.id, detected.first().zone.id)
    }

    @Test fun parserDottedAmPm() {
        // "p.m." form, dots get stripped before comparison.
        val detected = TimeParser.detect("call at 5p.m. CT")
        assertEquals(17, detected.first().hour)
    }

    @Test fun parserMultiWordZoneEastern() {
        val detected = TimeParser.detect("demo at 2:15pm eastern")
        assertEquals(14, detected.first().hour)
        assertEquals(15, detected.first().minute)
        assertEquals(et.id, detected.first().zone.id)
    }

    @Test fun parser12pmIsNoonNot12am() {
        // historic gotcha: 12pm in 12-hour clock is 12:00, NOT 0:00.
        val detected = TimeParser.detect("call at 12pm CT")
        assertEquals(12, detected.first().hour)
    }

    @Test fun parser12amIsMidnight() {
        // and 12am is 0:00, not 12:00.
        val detected = TimeParser.detect("deploy at 12am ET")
        assertEquals(0, detected.first().hour)
    }

    @Test fun converterHalfHourOffsetZone() {
        // India is UTC+5:30 — the :30 must propagate through to the rendered minutes.
        val ist = ZoneId.of("Asia/Kolkata")
        val detected = DetectedTime(
            hour = 19, minute = 0, zone = ist,
            hadExplicitZone = true,
            range = 0..6, originalText = "7pm IST",
        )
        val stamp = TimeConverter.renderStamp(detected, listOf(ist, et), now = anchor)
        // IST 7pm = ET 9:30am (Indian standard time runs +5:30, ET runs -5:00 in winter / -4:00 in summer;
        // anchor is May so ET = -4:00, delta = 9:30).
        assertTrue("expected ':30' in cross-zone render, got: $stamp", stamp.contains(":30"))
    }
}
