package com.timetwister.core

import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Guards the parser against going quadratic again.
 *
 * It was, twice over: two full-string copies ran once per match — the
 * unknown-zone lookahead and the "is there a time before this stamp" test — so
 * cost grew with (length x matches). Measured before the fix:
 *
 *   64 KB of zone-less times      561 ms
 *   64 KB of already-stamped text 2,359 ms
 *   128 KB of ordinary agenda     1,255 ms
 *   200 KB of already-stamped text 34,123 ms
 *
 * After bounding both to a region:
 *
 *   64 KB zone-less                47 ms
 *   64 KB stamped                  42 ms
 *   128 KB agenda                  17 ms
 *   200 KB stamped                192 ms
 *
 * Android had always capped its selection at 5000 characters and was safe. The
 * iOS Action Extension, the iOS keyboard (main thread, every keystroke) and the
 * desktop tray (EDT) had no cap, so the 34-second case was reachable by selecting
 * a long document and hitting share.
 *
 * The bounds below are absolute wall-clock, deliberately loose — roughly 25x the
 * measured time on this machine. They are not a benchmark and must not flake on a
 * slow or contended CI box; they exist to catch a return to quadratic, which
 * would blow through them by two orders of magnitude rather than a few percent.
 * Run with -Dtimetwister.demo=1 to see the actual timings.
 */
class PerformanceTest {

    @Test
    fun parsingLongTextStaysLinear() {
        // 200 KB of our own output — the worst measured case, because every stamp
        // triggers the look-behind that used to scan from the start of the string.
        assertUnder("200KB already-stamped", "5pm ET (6pm CT · 3pm PT) ".repeat(8200), 5_000)
    }

    @Test
    fun parsingLongZonelessTextStaysLinear() {
        // Zone-less times are the other quadratic path: with no zone to consume,
        // every match ran the unknown-zone lookahead over the rest of the string.
        assertUnder("64KB zone-less times", "12:30 ".repeat(10_666), 3_000)
    }

    @Test
    fun parsingOrdinaryLongTextIsCheap() {
        assertUnder(
            "128KB agenda text",
            "standup 9:30 then review 14:00 and retro 16:45 ".repeat(2_800),
            3_000,
        )
    }

    private fun assertUnder(label: String, text: String, budgetMs: Long) {
        // One untimed pass so JIT warm-up is not charged to the measurement.
        TimeConverter.splice(text, TARGETS, NOW)

        val start = System.nanoTime()
        TimeConverter.splice(text, TARGETS, NOW)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        if (System.getProperty("timetwister.demo") != null) {
            println("[perf] $label: ${text.length} chars in $elapsedMs ms (budget ${budgetMs} ms)")
        }
        assertTrue(
            "$label took ${elapsedMs}ms, over the ${budgetMs}ms budget. This budget is ~25x " +
                "the expected time, so this almost certainly means the parser went quadratic " +
                "again — look for a substring() or a regex run over the whole string inside " +
                "the per-match loop in TimeParser.detect, or in stampRanges.",
            elapsedMs < budgetMs,
        )
    }

    companion object {
        val NOW: ZonedDateTime = ZonedDateTime.parse("2026-05-18T09:00:00-04:00[America/New_York]")

        val TARGETS: List<ZoneId> = listOf(
            ZoneId.of("America/Chicago"),
            ZoneId.of("America/New_York"),
            ZoneId.of("America/Los_Angeles"),
        )

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
