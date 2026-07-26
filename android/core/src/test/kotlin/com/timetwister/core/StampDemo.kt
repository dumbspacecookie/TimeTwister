package com.timetwister.core

import org.junit.Assume
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Not an assertion test — runs the full parse + render pipeline on a fixed
 * set of canonical user inputs and prints the output stamps. Lives here as
 * executable documentation: anyone can see exactly what TimeTwister produces
 * without sideloading the APK.
 *
 * Gated on a system property so it stays out of normal `:core:test` runs:
 *
 *   ./gradlew :core:test --tests "*StampDemo*" -Dtimetwister.demo=1
 *
 * It used to carry a class-level `@Ignore` instead, which meant the command the
 * README told people to run reported "1 skipped", printed nothing, and exited
 * green — documentation that silently documented nothing. A property can be
 * turned on from the command line; an annotation cannot.
 *
 * The build wires the property through to the test JVM and turns on stdout
 * forwarding when it is set (see core/build.gradle.kts) — Gradle does neither
 * by default, which is the other half of why the old command appeared to work.
 */
class StampDemo {

    private val anchor: ZonedDateTime =
        ZonedDateTime.parse("2026-05-01T08:00:00-04:00[America/New_York]")

    private val targets = listOf(
        ZoneId.of("America/Chicago"),
        ZoneId.of("America/New_York"),
        ZoneId.of("America/Los_Angeles"),
    )

    @Test fun demoStampOutputs() {
        Assume.assumeTrue(
            "on-demand demo — re-run with -Dtimetwister.demo=1 to print the table",
            System.getProperty(DEMO_PROPERTY) != null,
        )

        val phrases = listOf(
            "lets do 5pm CT",
            "how about 5:30pm pacific",
            "landing at 17:00 ET",
            "call at noon CT",
            "deploy at midnight ET",
            "see you at noon",
            "standup at 9am PT",
            "demo at 2:15pm eastern",
            "ship by 11pm utc",
            "room 5 is open",                  // should NOT detect
            "meet at 7pm ist tomorrow",        // partial — picks 7pm IST
        )

        println()
        println("=== TimeTwister stamp demo (anchor: ${anchor.toLocalDate()}) ===")
        println("targets: CT, ET, PT")
        println()
        for (phrase in phrases) {
            val detected = TimeParser.detect(phrase)
            if (detected.isEmpty()) {
                println("  IN:  '$phrase'")
                println("  OUT: (no time detected)")
                println()
                continue
            }
            for (d in detected) {
                val stamp = TimeConverter.renderStamp(d, targets, now = anchor)
                val replaced = phrase.replaceRange(d.range, stamp)
                println("  IN:  '$phrase'")
                println("  HIT: '${d.originalText}'  →  hour=${d.hour} min=${d.minute} zone=${d.zone.id} explicit=${d.hadExplicitZone}")
                println("  OUT: '$replaced'")
                println()
            }
        }
    }

    companion object {
        /** Also referenced by core/build.gradle.kts — keep the two in step. */
        const val DEMO_PROPERTY = "timetwister.demo"
    }
}
