package com.timetwister.core

import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random
import java.util.TimeZone

/**
 * Property / invariant tests.
 *
 * Where EvalCorpusTest grades fixed examples, this file states things that must
 * hold for *every* input and then hunts for counterexamples with hand-rolled
 * generators. No property-testing library: only kotlin stdlib, java.time and
 * java.util.Random, seeded so that a red run reproduces exactly.
 *
 * Run a different seed with:
 *   ./gradlew :core:test --tests '*PropertyTest' -Dtimetwister.eval.seed=12345
 * The seed in use is printed by every property, pass or fail.
 *
 * Like the corpus, properties are split into ENFORCED (a counterexample fails
 * the build) and reported. A reported property is one the implementation is
 * known to violate today; the counterexample is printed loudly so it stays
 * visible instead of being deleted.
 */
class PropertyTest {

    // -----------------------------------------------------------------------
    // ENFORCED properties
    // -----------------------------------------------------------------------

    @Test
    fun spliceNeverLosesTheSurroundingText() {
        runProperty(PROP_PRESERVES_CONTEXT) { input, targets ->
            val detected = TimeParser.detectLast(input) ?: return@runProperty null
            val prefix = input.substring(0, detected.range.first)
            val suffix = input.substring(detected.range.last + 1)
            val out = TimeConverter.splice(input, targets, NOW)
            when {
                !out.startsWith(prefix) ->
                    "prefix lost: expected output to start with " + show(prefix) +
                        " but got " + show(out)
                !out.endsWith(suffix) ->
                    "suffix lost: expected output to end with " + show(suffix) +
                        " but got " + show(out)
                out.length < prefix.length + suffix.length ->
                    "output is shorter than the untouched context: " + show(out)
                else -> null
            }
        }
    }

    @Test
    fun spliceReturnsTheSameInstanceWhenNothingIsDetected() {
        runProperty(PROP_NO_DETECT_IDENTITY) { input, targets ->
            if (TimeParser.detect(input).isNotEmpty()) return@runProperty null
            val out = TimeConverter.splice(input, targets, NOW)
            if (out !== input) {
                "detect() was empty so splice() must hand back the very same instance, " +
                    "but it returned " + show(out)
            } else {
                null
            }
        }
    }

    @Test
    fun stampAlwaysLeadsWithTheSourceLabel() {
        runProperty(PROP_SOURCE_LABEL_FIRST) { input, targets ->
            val detected = TimeParser.detectLast(input) ?: return@runProperty null
            val stamp = TimeConverter.renderStamp(detected, targets, NOW)
            val head = headOf(stamp)
            // Labels are resolved against the instant being rendered (so Paris reads
            // CEST in summer, not a flat CET), which means the expectation has to be
            // computed at that same instant rather than from the zone alone.
            val label = TimeZoneAlias.shortLabel(
                detected.zone,
                TimeConverter.absoluteInstant(detected, NOW),
            )
            if (!head.endsWith(" " + label)) {
                "stamp must lead with the source zone: head " + show(head) +
                    " does not end with the source label '" + label + "'. stamp=" + show(stamp)
            } else {
                null
            }
        }
    }

    @Test
    fun stampTargetsAreDistinctAndNeverRepeatTheSource() {
        runProperty(PROP_DISTINCT_TARGETS) { input, targets ->
            val detected = TimeParser.detectLast(input) ?: return@runProperty null
            val stamp = TimeConverter.renderStamp(detected, targets, NOW)
            if (!stamp.endsWith(")")) return@runProperty null // no conversions rendered
            val sourceLabel = TimeZoneAlias.shortLabel(
                detected.zone,
                TimeConverter.absoluteInstant(detected, NOW),
            )
            val labels = renderedTargetLabels(stamp)
            when {
                labels.size != labels.toSet().size ->
                    "duplicate target zone in stamp " + show(stamp)
                labels.contains(sourceLabel) ->
                    "source zone '" + sourceLabel + "' rendered again as a target in " + show(stamp)
                else -> null
            }
        }
    }

    @Test
    fun renderedStampRoundTripsToTheSameInstant() {
        runProperty(PROP_ROUND_TRIP) { input, targets ->
            val detected = TimeParser.detectLast(input) ?: return@runProperty null
            val label = TimeZoneAlias.shortLabel(detected.zone)
            // Labels our own parser cannot read back are the subject of
            // PROP_LABELS_REPARSE below; do not double-report them here.
            if (TimeZoneAlias.resolve(label)?.id != detected.zone.id) return@runProperty null

            val head = headOf(TimeConverter.renderStamp(detected, targets, NOW))
            val reparsed = TimeParser.detectLast(head)
                ?: return@runProperty "rendered stamp head " + show(head) + " does not re-parse at all"
            // Same wall time in the same zone, evaluated against the same clock,
            // is the same instant by construction of TimeConverter.absoluteInstant.
            if (reparsed.hour != detected.hour ||
                reparsed.minute != detected.minute ||
                reparsed.zone.id != detected.zone.id
            ) {
                "round-trip drifted: " + detected.hour + ":" + detected.minute + " " +
                    detected.zone.id + " rendered as " + show(head) + " and read back as " +
                    reparsed.hour + ":" + reparsed.minute + " " + reparsed.zone.id
            } else {
                null
            }
        }
    }

    @Test
    fun detectIsStableUnderSurroundingWhitespace() {
        runProperty(PROP_WHITESPACE_STABLE) { input, _ ->
            val bare = TimeParser.detect(input)
            val padded = TimeParser.detect("  " + input + "  ")
            if (bare.size != padded.size) {
                return@runProperty "padding changed the number of detections: " +
                    bare.size + " -> " + padded.size
            }
            for (i in bare.indices) {
                val a = bare[i]
                val b = padded[i]
                if (a.hour != b.hour || a.minute != b.minute || a.zone.id != b.zone.id) {
                    return@runProperty "padding changed detection #" + i + ": " +
                        a.hour + ":" + a.minute + " " + a.zone.id + " -> " +
                        b.hour + ":" + b.minute + " " + b.zone.id
                }
            }
            null
        }
    }

    @Test
    fun spliceNeverThrows() {
        runProperty(PROP_NEVER_THROWS) { input, targets ->
            TimeParser.detect(input)
            TimeConverter.splice(input, targets, NOW)
            TimeConverter.maybeSplice(input, targets, readOnly = false, now = NOW)
            null
        }
    }

    // -----------------------------------------------------------------------
    // REPORTED properties - known violations, printed but not fatal
    // -----------------------------------------------------------------------

    @Test
    fun spliceIsIdempotent() {
        runProperty(PROP_IDEMPOTENT) { input, targets ->
            val once = TimeConverter.splice(input, targets, NOW)
            val twice = TimeConverter.splice(once, targets, NOW)
            if (once != twice) {
                "second pass changed the text again:\n" +
                    "        once  " + show(once) + "\n" +
                    "        twice " + show(twice)
            } else {
                null
            }
        }
    }

    @Test
    fun everyRenderedZoneLabelCanBeReadBackByOurOwnParser() {
        // Deterministic, not generated: walk every zone the alias table can produce.
        val violations = ArrayList<String>()
        val zones = TimeZoneAlias.map.values.toSortedSet()
        for (id in zones) {
            val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: continue
            // Label it the way the renderer does — against the instant being shown.
            // Zones that observe DST have no single correct abbreviation, so asking
            // for one without an instant yields a bare offset that no zone id can
            // round-trip; that is a property of the question, not a defect.
            val label = TimeZoneAlias.shortLabel(zone, NOW)
            val back = TimeZoneAlias.resolve(label)
            if (back == null || back.id != zone.id) {
                violations.add(
                    "  " + id + " renders as '" + label + "' but resolve('" + label + "') = " +
                        (back?.id ?: "null") + " -- a stamp we produced cannot be re-read by us",
                )
            }
        }
        finish(PROP_LABELS_REPARSE, violations, zones.size)
    }

    // -----------------------------------------------------------------------
    // harness
    // -----------------------------------------------------------------------

    /**
     * Runs [body] over [CASES] generated inputs. [body] returns null when the
     * property holds and a human-readable violation otherwise.
     */
    private fun runProperty(name: String, body: (String, List<ZoneId>) -> String?) {
        val random = Random(SEED)
        val violations = ArrayList<String>()
        for (i in 0 until CASES) {
            val input = generateInput(random)
            val targets = TARGET_SETS[random.nextInt(TARGET_SETS.size)]
            val result = try {
                body(input, targets)
            } catch (t: Throwable) {
                "threw " + t.javaClass.name + ": " + t.message
            }
            if (result != null) {
                if (violations.size < MAX_REPORTED_VIOLATIONS) {
                    violations.add(
                        "  case #" + i + "\n" +
                            "        input   " + show(input) + "\n" +
                            "        targets " + targets.joinToString(", ") {
                                TimeZoneAlias.shortLabel(it)
                            }.ifEmpty { "(none)" } + "\n" +
                            "        " + result,
                    )
                } else if (violations.size == MAX_REPORTED_VIOLATIONS) {
                    violations.add("  ... more violations suppressed")
                }
            }
        }
        finish(name, violations, CASES)
    }

    private fun finish(name: String, violations: List<String>, checked: Int) {
        val enforced = ENFORCED_PROPERTIES.contains(name)
        val gate = if (enforced) "ENFORCED" else "reported"
        val verdict = if (violations.isEmpty()) "HOLDS" else "VIOLATED"
        println(
            "[property] " + pad(name, 26) + pad(gate, 10) + pad(verdict, 10) +
                checked + " cases   seed=" + SEED,
        )
        if (violations.isEmpty()) return

        val body = StringBuilder()
        body.append('\n')
        body.append(if (enforced) "PROPERTY FAILED: " else "KNOWN PROPERTY VIOLATION: ").append(name)
        body.append("  (seed=").append(SEED).append(", reproduce with ")
        body.append("-Dtimetwister.eval.seed=").append(SEED).append(")\n")
        for (v in violations) body.append(v).append('\n')
        println(body.toString())

        if (enforced) {
            fail(
                "Property '" + name + "' has " + violations.size + " counterexample(s). " +
                    "Seed=" + SEED + " -- rerun with -Dtimetwister.eval.seed=" + SEED +
                    " to reproduce. Details printed above.",
            )
        }
    }

    // -----------------------------------------------------------------------
    // generators
    // -----------------------------------------------------------------------

    private fun generateInput(random: Random): String {
        val prefix = PREFIXES[random.nextInt(PREFIXES.size)]
        val core = generateTimeExpression(random)
        val suffix = SUFFIXES[random.nextInt(SUFFIXES.size)]
        val text = prefix + core + suffix
        // 1 in 12 inputs gets a stray astral character glued on, to keep the
        // UTF-16 index arithmetic in splice() under pressure.
        return if (random.nextInt(12) == 0) ASTRAL[random.nextInt(ASTRAL.size)] + text else text
    }

    private fun generateTimeExpression(random: Random): String {
        if (random.nextInt(10) == 0) {
            val keyword = if (random.nextBoolean()) "noon" else "midnight"
            return keyword + zoneToken(random)
        }
        val twelveHour = random.nextBoolean()
        val hour = if (twelveHour) 1 + random.nextInt(12) else random.nextInt(24)
        val sb = StringBuilder()
        if (!twelveHour && random.nextBoolean() && hour < 10) sb.append('0')
        sb.append(hour)
        if (random.nextInt(3) != 0) {
            sb.append(':')
            val minute = random.nextInt(60)
            if (minute < 10) sb.append('0')
            sb.append(minute)
        }
        if (twelveHour && random.nextInt(4) != 0) {
            sb.append(MERIDIEMS[random.nextInt(MERIDIEMS.size)])
        }
        sb.append(zoneToken(random))
        return sb.toString()
    }

    private fun zoneToken(random: Random): String {
        if (random.nextInt(5) == 0) return ""
        val separator = SEPARATORS[random.nextInt(SEPARATORS.size)]
        return separator + ZONE_TOKENS[random.nextInt(ZONE_TOKENS.size)]
    }

    // -----------------------------------------------------------------------
    // small helpers
    // -----------------------------------------------------------------------

    /** The "5pm CT" part of "5pm CT (6pm ET ...)". */
    private fun headOf(stamp: String): String {
        val index = stamp.indexOf(" (")
        return if (index < 0) stamp else stamp.substring(0, index)
    }

    /**
     * ["ET", "PT", "UK"] out of "5pm CT (6pm ET . 3pm PT . 11pm UK)".
     *
     * A conversion that lands on another calendar day carries a trailing day marker
     * ("1am CT +1d"), which is not part of the zone label and has to come off first
     * — otherwise every cross-midnight target reads as a zone called "+1d".
     */
    private fun renderedTargetLabels(stamp: String): List<String> {
        val open = stamp.indexOf(" (")
        if (open < 0) return emptyList()
        val inner = stamp.substring(open + 2, stamp.length - 1)
        return inner.split(SEPARATOR_TOKEN).map { part ->
            part.trim().split(' ').filterNot { it.matches(DAY_MARKER) }.last()
        }
    }

    private fun pad(s: String, width: Int): String = s.padEnd(width)

    private fun show(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            val code = ch.code
            when {
                ch == '\t' -> sb.append("\\t")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '"' -> sb.append("\\\"")
                code < 0x20 || code == 0x7F || code == 0xA0 || code >= 0xD800 ->
                    sb.append("\\u").append(
                        Integer.toHexString(code).uppercase().padStart(4, '0'),
                    )
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {

        // -------------------------------------------------------------------
        // THE RATCHET. Move a property name from the comment block below into
        // ENFORCED_PROPERTIES once the implementation actually satisfies it.
        // Never delete a property to make the build green - reported properties
        // are the record of what is still broken.
        // -------------------------------------------------------------------
        val ENFORCED_PROPERTIES: Set<String> = linkedSetOf(
            PROP_PRESERVES_CONTEXT,
            PROP_NO_DETECT_IDENTITY,
            PROP_SOURCE_LABEL_FIRST,
            PROP_DISTINCT_TARGETS,
            PROP_ROUND_TRIP,
            PROP_WHITESPACE_STABLE,
            PROP_NEVER_THROWS,
            // Ratcheted up 2026-07-25, both previously known-violated:
            //   PROP_IDEMPOTENT      splice recognises its own stamp and refreshes
            //                        it in place instead of nesting a new one
            //   PROP_LABELS_REPARSE  every label we emit is now an alias token, so
            //                        a stamp can always be read back by the parser
            PROP_IDEMPOTENT,
            PROP_LABELS_REPARSE,
        )

        const val CASES: Int = 500
        const val MAX_REPORTED_VIOLATIONS: Int = 8
        const val DEFAULT_SEED: Long = 20260518L
        const val SEED_PROPERTY: String = "timetwister.eval.seed"

        /** Overridable so a red CI run can be replayed locally verbatim. */
        val SEED: Long = System.getProperty(SEED_PROPERTY)?.toLongOrNull() ?: DEFAULT_SEED

        /** Same fixed clock as EvalCorpusTest. Never ZonedDateTime.now(). */
        val NOW: ZonedDateTime = ZonedDateTime.parse("2026-05-18T09:00:00-04:00[America/New_York]")

        /** Distinct-by-construction target sets, including the empty one. */
        val TARGET_SETS: List<List<ZoneId>> = listOf(
            listOf(
                ZoneId.of("America/Chicago"),
                ZoneId.of("America/New_York"),
                ZoneId.of("America/Los_Angeles"),
                ZoneId.of("Europe/London"),
            ),
            listOf(ZoneId.of("America/New_York"), ZoneId.of("America/Los_Angeles")),
            listOf(ZoneId.of("America/New_York")),
            listOf(ZoneId.of("UTC"), ZoneId.of("Asia/Tokyo"), ZoneId.of("Asia/Kolkata")),
            listOf(
                ZoneId.of("America/Chicago"),
                ZoneId.of("America/New_York"),
                ZoneId.of("America/Los_Angeles"),
            ),
            emptyList(),
        )

        const val PINNED_DEFAULT_ZONE: String = "America/New_York"

        /** U+00B7 MIDDLE DOT, the separator TimeConverter renders between zones. */
        const val SEPARATOR_TOKEN: String = " \u00B7 "

        /** The "+1d" / "-2d" suffix on a conversion that lands on another date. */
        val DAY_MARKER: Regex = Regex("""[+-]\d+d""")

        private val PREFIXES: Array<String> = arrayOf(
            "", "", "meet at ", "lets do ", "call ", "sync ", "ping me by ", "deadline ",
            "room 5 is open, ", "part 3 then ", "9-5 job, ", "hola ", "\u0645\u0631\u062D\u0628\u0627 ",
            "\uD83C\uDF89 ", "the top 5 est. results, ",
        )

        private val SUFFIXES: Array<String> = arrayOf(
            "", "", " tomorrow", " ok?", "!", ".", ", thanks", " sharp", "\n", " \uD83D\uDE80",
            " and then dinner",
        )

        private val MERIDIEMS: Array<String> = arrayOf(
            "am", "pm", "AM", "PM", " am", " pm", "a.m.", "p.m.", " p.m.",
        )

        private val SEPARATORS: Array<String> = arrayOf(" ", " ", "  ", "\t")

        private val ZONE_TOKENS: Array<String> = arrayOf(
            "et", "est", "edt", "ct", "cst", "cdt", "mt", "mst", "mdt", "pt", "pst", "pdt",
            "akst", "akdt", "hst", "utc", "gmt", "bst", "cet", "cest", "eet", "eest",
            "ist", "jst", "kst", "sgt", "hkt", "aest", "aedt", "nzst", "nzdt",
            "eastern", "central", "mountain", "pacific", "alaska", "hawaii",
            "london", "tokyo", "singapore", "sydney", "india",
            "ET", "CT", "PT", "UTC", "IST", "Pacific",
        )

        private val ASTRAL: Array<String> = arrayOf(
            "\uD83C\uDF89", "\uD83D\uDE80", "\uD83D\uDC4D", "\uD83D\uDE00\uD83D\uDE00",
        )

        private var savedDefaultZone: TimeZone? = null

        @BeforeClass
        @JvmStatic
        fun pinDefaultZone() {
            savedDefaultZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone(PINNED_DEFAULT_ZONE))
            println(
                "[property] seed=" + SEED + " (override with -D" + SEED_PROPERTY + "=<long>), " +
                    "cases=" + CASES + ", system zone pinned to " + PINNED_DEFAULT_ZONE,
            )
        }

        @AfterClass
        @JvmStatic
        fun restoreDefaultZone() {
            savedDefaultZone?.let { TimeZone.setDefault(it) }
            savedDefaultZone = null
        }
    }
}

// Property names live at file scope so ENFORCED_PROPERTIES can reference them
// from the companion object's initializer without ordering problems.
private const val PROP_PRESERVES_CONTEXT: String = "preserves-context"
private const val PROP_NO_DETECT_IDENTITY: String = "no-detect-identity"
private const val PROP_SOURCE_LABEL_FIRST: String = "source-label-first"
private const val PROP_DISTINCT_TARGETS: String = "distinct-targets"
private const val PROP_ROUND_TRIP: String = "round-trip"
private const val PROP_WHITESPACE_STABLE: String = "whitespace-stable"
private const val PROP_NEVER_THROWS: String = "never-throws"
private const val PROP_IDEMPOTENT: String = "idempotent"
private const val PROP_LABELS_REPARSE: String = "labels-reparse"
