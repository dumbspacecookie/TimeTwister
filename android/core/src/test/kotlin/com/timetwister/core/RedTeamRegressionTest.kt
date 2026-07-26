package com.timetwister.core

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * One test per defect found in the 2026-07-25 red-team pass, each pinned to the
 * exact input that produced the bad output. Every case here was confirmed by
 * running the old code, not by reading it — the strings in the comments are what
 * the tool actually used to put into a user's message.
 *
 * The clock and the system zone are pinned; nothing here may depend on wall time.
 */
class RedTeamRegressionTest {

    private val ct = ZoneId.of("America/Chicago")
    private val et = ZoneId.of("America/New_York")
    private val pt = ZoneId.of("America/Los_Angeles")
    private val targets = listOf(ct, et, pt)

    private fun splice(input: String) = TimeConverter.splice(input, targets, NOW)

    // -- RT-01 -------------------------------------------------------------
    // Was: "lets do 5pm CT (6pm ET · 3pm PT)" run twice produced
    //      "lets do 5pm CT (6pm ET · 3pm PT (5pm CT · 6pm ET))"
    @Test
    fun secondPassDoesNotNestTheStampInsideItself() {
        val once = splice("lets do 5pm CT")
        val twice = splice(once)
        assertEquals("a second pass must be a no-op", once, twice)
        assertEquals("lets do 5pm CT (6pm ET · 3pm PT)", once)
    }

    @Test
    fun repeatedPassesConverge() {
        var text = "meet at 7pm IST tomorrow"
        val first = splice(text)
        repeat(5) { text = splice(if (it == 0) text else text) }
        assertEquals(first, splice(first))
    }

    // -- RT-02 -------------------------------------------------------------
    // Was: "use 12 pt font" -> "use 12pm PT (2pm CT · 3pm ET) font", and friends.
    // A bare number next to a zone token is not a time.
    @Test
    fun bareNumberPlusZoneTokenIsNotATime() {
        for (input in listOf(
            "use 12 pt font",
            "make the header 18 pt",
            "I'm at 5 London Road",
            "top 5 est. results",
            "1 ct diamond",
            "12 Central Ave",
            "won 3-1 pt",
            "score was 12-3 mt",
            "half past 5 ET",
            "Section 5 pt 2",
            "converted 5 ct to usd",
            "he dropped 40 pt",
        )) {
            assertEquals("must not rewrite: $input", input, splice(input))
        }
    }

    // -- RT-04 / RT-08 -----------------------------------------------------
    // Was: a comma, bracket or non-breaking space between the time and the zone
    // broke the match, and the time was silently re-attributed to the SYSTEM zone
    // and rendered with full confidence — wrong by hours, with no signal.
    @Test
    fun separatorsBetweenTimeAndZoneDoNotLoseTheZone() {
        val expected = "5pm CT (6pm ET · 3pm PT)"
        for (input in listOf(
            "5pm CT", "5pm, CT", "5pm (CT)", "5pm [CT]",
            "5pm CT", "5pm CT", "5pm\nCT", "5pm\tCT", "5pm  CT",
        )) {
            val detected = TimeParser.detectLast(input)
            assertTrue("zone must be explicit for: $input", detected!!.hadExplicitZone)
            assertEquals(ct.id, detected.zone.id)
        }
        assertEquals(expected, splice("5pm CT"))
    }

    // -- RT-03 / RT-07 -----------------------------------------------------
    // Was: "5pm Eastern Time" -> "5pm ET (…) Time"  (dangling word)
    //      "5pm EST/EDT"      -> "5pm ET (…)/EDT"   (dangling fragment)
    //      "3pm Central European Time" resolved to US Central, not Paris.
    @Test
    fun spelledOutAndPairedZonesLeaveNothingDangling() {
        assertEquals("call me at 5pm ET (4pm CT · 2pm PT)", splice("call me at 5pm Eastern Time"))
        assertEquals("lets do 5pm ET (4pm CT · 2pm PT)", splice("lets do 5pm EST/EDT"))
        assertEquals(
            "central european must not resolve to US Central",
            "Europe/Paris",
            TimeParser.detectLast("3pm Central European Time")!!.zone.id,
        )
    }

    // -- RT-05 -------------------------------------------------------------
    // Was: "5pm America/Toronto (…)" — a raw IANA id rendered into a chat message.
    @Test
    fun unlistedZonesNeverRenderARawIanaId() {
        for (id in listOf("America/Toronto", "Europe/Berlin", "Europe/Madrid", "Asia/Dubai")) {
            val zone = ZoneId.of(id)
            val label = TimeZoneAlias.shortLabel(zone, NOW)
            assertTrue("label for $id must not be an IANA id, got '$label'", !label.contains('/'))
        }
    }

    // -- RT-05 (secondary) -------------------------------------------------
    // Was: targets on the same offset as the source were listed anyway, e.g.
    // "5pm America/Toronto (5pm ET · …)" — width with no information.
    @Test
    fun targetsSharingTheSourceOffsetAreNotRepeated() {
        val toronto = ZoneId.of("America/Toronto")
        val detected = DetectedTime(17, 0, toronto, true, 0..0, "5pm")
        val stamp = TimeConverter.renderStamp(detected, listOf(toronto, et, ct, pt), NOW)
        assertTrue("must not repeat the source offset: $stamp", !stamp.contains("5pm ET"))
    }

    // -- RT-06 -------------------------------------------------------------
    // Was: "meet at 11pm PT (1am CT · 2am ET)" with no day marker — an ET reader
    // sees "2am" and books tonight.
    @Test
    fun conversionsOnAnotherCalendarDayAreMarked() {
        assertEquals("meet at 11pm PT (1am CT +1d · 2am ET +1d)", splice("meet at 11pm PT"))
        assertEquals("deploy at 12am ET (11pm CT -1d · 9pm PT -1d)", splice("deploy at 12am ET"))
    }

    // -- RT-11 -------------------------------------------------------------
    // Was: "deploy at 2:30am ET" on a spring-forward day -> "deploy at 3:30am ET (…)",
    // silently replacing a time the user actually typed with one they did not.
    @Test
    fun aTimeInsideTheDstGapIsLeftAlone() {
        val springForward = ZonedDateTime.of(2026, 3, 8, 0, 30, 0, 0, et)
        val input = "deploy at 2:30am ET"
        assertEquals(input, TimeConverter.splice(input, targets, springForward))
        assertNull(TimeConverter.maybeSplice(input, targets, readOnly = false, now = springForward))
    }

    // -- D6: the OTHER DST edge, pinned rather than fixed --------------------
    /**
     * An autumn fall-back makes a wall clock happen twice. 2026-11-01 in
     * America/New_York rewinds at 2am, so "1:30am" names two instants an hour
     * apart: 05:30Z at -04:00, and 06:30Z at -05:00.
     *
     * We take the first, silently. That is a deliberate decision, not an
     * oversight, and this test exists so it stays one:
     *
     *  - It is not the same problem as the spring-forward gap. That one asks us to
     *    invent a time that does not exist, so [isUnrepresentable] refuses it. This
     *    one asks us to choose between two that do, and an ambiguous time
     *    round-trips perfectly, so that guard structurally cannot fire here.
     *  - The earlier instant is the conventional reading — it is what
     *    `ZonedDateTime.of` and every calendar application default to.
     *  - The exposure is one hour, once a year, per zone, at 1-2am.
     *
     * Refusing would decline an ordinary-looking input with an explanation nobody
     * wants ("it happens twice" — they mean the first one). If that trade is ever
     * revisited, this test is what will tell you the behaviour moved.
     */
    @Test
    fun anAmbiguousWallClockResolvesToTheEarlierOfItsTwoInstants() {
        val eveningBefore = ZonedDateTime.of(2026, 10, 31, 20, 0, 0, 0, et)
        val detected = TimeParser.detectLast("call at 1:30am ET")!!

        assertTrue(
            "an ambiguous time is representable — the gap guard must not fire",
            !TimeConverter.isUnrepresentable(detected, eveningBefore),
        )
        val resolved = TimeConverter.absoluteInstant(detected, eveningBefore)
        assertEquals(java.time.ZoneOffset.ofHours(-4), resolved.offset)
        assertEquals("2026-11-01T05:30:00Z", resolved.toInstant().toString())
    }

    // -- RT-12 -------------------------------------------------------------
    // Was: Australia/Sydney rendered "AEST" in January (it is on AEDT) and Paris
    // rendered "CET" all summer. The number was right, the label contradicted it.
    @Test
    fun dstSpecificLabelsFollowTheInstant() {
        val sydney = ZoneId.of("Australia/Sydney")
        val paris = ZoneId.of("Europe/Paris")
        val january = ZonedDateTime.of(2026, 1, 15, 8, 0, 0, 0, et)
        val july = ZonedDateTime.of(2026, 7, 15, 8, 0, 0, 0, et)
        assertEquals("AEDT", TimeZoneAlias.shortLabel(sydney, january))
        assertEquals("AEST", TimeZoneAlias.shortLabel(sydney, july))
        assertEquals("CET", TimeZoneAlias.shortLabel(paris, january))
        assertEquals("CEST", TimeZoneAlias.shortLabel(paris, july))
    }

    // -- G014-G017 ---------------------------------------------------------
    // Was: "call 7pm NPT" -> "call 7pm ET (…) NPT". The writer named a zone we do
    // not support; guessing the system zone relabels their time as something else.
    @Test
    fun anUnsupportedZoneTokenSuppressesTheGuess() {
        for (input in listOf("call 7pm NPT", "call 7pm ACST", "call 7pm MSK", "call 7pm WAT")) {
            assertEquals("must not guess a zone for: $input", input, splice(input))
        }
    }

    // -- G021 --------------------------------------------------------------
    // Was: "12 noon ET" -> "12 12pm ET (…)" — the leading 12 stranded.
    @Test
    fun twelveNoonAbsorbsTheLeadingTwelve() {
        assertEquals("12pm ET (11am CT · 9am PT)", splice("12 noon ET"))
    }

    // -- Unicode -----------------------------------------------------------
    // Was: "5pm CTです" lost the zone, because Java's \b is Unicode-aware and there
    // is no boundary between "T" and "で".
    @Test
    fun nonLatinTextTouchingTheZoneTokenKeepsTheZone() {
        assertEquals(ct.id, TimeParser.detectLast("5pm CTです")!!.zone.id)
        assertEquals("5pm CT (6pm ET · 3pm PT)です", splice("5pm CTです"))
    }

    // A token running into more ASCII letters is still not a zone.
    @Test
    fun zoneTokensDoNotMatchInsideLongerWords() {
        assertEquals("meeting at 5pmm", splice("meeting at 5pmm"))
        assertEquals("5pm CTX build", splice("5pm CTX build"))
    }

    companion object {
        /** Same fixed clock the eval corpus uses. */
        val NOW: ZonedDateTime = ZonedDateTime.parse("2026-05-18T09:00:00-04:00[America/New_York]")

        private var saved: TimeZone? = null

        @BeforeClass
        @JvmStatic
        fun pinDefaultZone() {
            saved = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        }

        @AfterClass
        @JvmStatic
        fun restoreDefaultZone() {
            saved?.let { TimeZone.setDefault(it) }
        }
    }
}
