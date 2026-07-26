package com.timetwister.app

import com.timetwister.core.TimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The five outcomes ACTION_PROCESS_TEXT can produce. This is the app's entire feature and
 * it had no test of any kind until the logic was lifted out of ProcessTextActivity.onCreate,
 * where Toast/clipboard/Intent calls put it out of reach of a plain JVM test.
 */
class ProcessTextDecisionTest {

    private val et = ZoneId.of("America/New_York")
    private val targets = listOf(
        ZoneId.of("America/New_York"),
        ZoneId.of("America/Chicago"),
        ZoneId.of("America/Los_Angeles"),
    )

    /** A fixed clock: every assertion below would otherwise drift across a DST boundary. */
    private val now = ZonedDateTime.of(2026, 7, 20, 9, 0, 0, 0, et)

    private fun decide(
        input: String,
        readOnly: Boolean = false,
        now: ZonedDateTime = this.now,
        onTargets: () -> Unit = {},
    ) = ProcessTextDecider.decide(input, readOnly, now) {
        onTargets()
        targets
    }

    // ---- refusal paths ----

    @Test
    fun `an over-long selection is refused`() {
        val input = "a".repeat(TimeParser.MAX_INPUT_CHARS + 1)
        assertEquals(ProcessTextDecision.TooLong, decide(input))
    }

    @Test
    fun `refusing an over-long selection never loads the zone list`() {
        // The ordering matters, not just the outcome: loading targets can fall through to
        // a blocking DataStore read on the main thread, and the whole point of the cap is
        // to refuse absurd input without doing work. Previously enforced only by the order
        // of two statements in onCreate, where nothing could check it.
        var loaded = false
        decide("a".repeat(TimeParser.MAX_INPUT_CHARS + 1), onTargets = { loaded = true })
        assertFalse("targets were loaded for an input we refuse", loaded)
    }

    @Test
    fun `a selection exactly at the cap is still processed`() {
        val padding = "x".repeat(TimeParser.MAX_INPUT_CHARS - "meet at 5pm ET".length)
        val input = padding + "meet at 5pm ET"
        assertEquals(TimeParser.MAX_INPUT_CHARS, input.length)
        assertTrue(decide(input) is ProcessTextDecision.Replace)
    }

    @Test
    fun `text with no time reference is declined`() {
        assertEquals(ProcessTextDecision.NoTimeFound, decide("lunch sometime next week"))
    }

    @Test
    fun `a bare hour with no am pm is declined rather than guessed`() {
        assertEquals(ProcessTextDecision.NoTimeFound, decide("call me at 5 ET"))
    }

    @Test
    fun `declining also skips the zone list load`() {
        var loaded = false
        decide("no times here at all", onTargets = { loaded = true })
        assertFalse(loaded)
    }

    @Test
    fun `a time inside the spring forward gap is refused, not shifted`() {
        // 2026-03-08 02:30 does not exist in ET. ZonedDateTime.of silently returns 03:30,
        // which would rewrite the user's own words to a time they did not type.
        val gapDay = ZonedDateTime.of(2026, 3, 8, 0, 30, 0, 0, et)
        val decision = decide("deploy at 2:30am ET", now = gapDay)
        assertEquals(ProcessTextDecision.TimeDoesNotExist("2:30am ET"), decision)
    }

    // ---- editable host ----

    @Test
    fun `an editable host gets the rewritten selection`() {
        val decision = decide("standup at 5pm ET tomorrow")
        assertTrue(decision.toString(), decision is ProcessTextDecision.Replace)
        val text = (decision as ProcessTextDecision.Replace).text
        assertTrue(text, text.startsWith("standup at 5pm ET"))
        assertTrue(text, text.endsWith("tomorrow"))
        assertTrue("no stamp in: $text", "(" in text && "PT" in text)
    }

    @Test
    fun `deciding twice is idempotent`() {
        // Re-running on already-stamped text must refresh the stamp in place, not nest a
        // second one inside the first.
        val once = (decide("standup at 5pm ET") as ProcessTextDecision.Replace).text
        val twice = (decide(once) as ProcessTextDecision.Replace).text
        assertEquals(once, twice)
    }

    @Test
    fun `text around the time is preserved exactly`() {
        val decision = decide("[ping] see you at 5pm ET — bring notes") as ProcessTextDecision.Replace
        assertTrue(decision.text, decision.text.startsWith("[ping] see you at 5pm ET"))
        assertTrue(decision.text, decision.text.endsWith(" — bring notes"))
    }

    // ---- read-only host ----

    @Test
    fun `a read-only host gets the whole selection for the clipboard`() {
        val decision = decide("their message: call at 5pm ET", readOnly = true)
        assertTrue(decision.toString(), decision is ProcessTextDecision.CopyToClipboard)
        decision as ProcessTextDecision.CopyToClipboard
        // The user selected a sentence, so pasting back a sentence is what they meant.
        assertTrue(decision.text, decision.text.startsWith("their message: call at 5pm ET"))
    }

    @Test
    fun `the read-only stamp is the stamp alone, not the sentence`() {
        val decision =
            decide("their message: call at 5pm ET", readOnly = true) as ProcessTextDecision.CopyToClipboard
        assertFalse(
            "the toast should show the stamp, not the whole selection: ${decision.stamp}",
            "their message" in decision.stamp,
        )
        assertTrue(decision.stamp, "5pm ET" in decision.stamp)
    }

    @Test
    fun `what a read-only host copies and what it shows agree`() {
        // Two separate render calls on one clock reading. When they were two clock
        // readings, a selection converted across midnight could show one time and paste
        // another.
        val decision =
            decide("call at 11pm ET", readOnly = true) as ProcessTextDecision.CopyToClipboard
        assertTrue(
            "shown stamp ${decision.stamp} is not in copied text ${decision.text}",
            decision.stamp in decision.text,
        )
    }

    @Test
    fun `read-only refusals are refusals, not silent copies`() {
        // A read-only selection with nothing in it must not put the unchanged text on the
        // clipboard and claim success.
        assertEquals(
            ProcessTextDecision.NoTimeFound,
            decide("just some prose", readOnly = true),
        )
    }
}
