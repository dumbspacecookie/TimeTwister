package com.timetwister.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.ZoneId

class UserConfigTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultsWhenFileMissing() {
        val cfg = UserConfig(tmp.root.toPath().resolve("missing/zones.txt"))
        val zones = cfg.targetZones()
        assertTrue("defaults must include America/New_York", zones.any { it.id == "America/New_York" })
        assertTrue("defaults must include America/Los_Angeles", zones.any { it.id == "America/Los_Angeles" })
    }

    @Test fun roundTripPreservesOrder() {
        val cfg = UserConfig(tmp.root.toPath().resolve("zones.txt"))
        val want = listOf("Europe/London", "Asia/Tokyo", "America/New_York").map(ZoneId::of)
        cfg.setTargetZones(want)
        assertEquals(want, cfg.targetZones())
    }

    @Test fun ignoresCommentsAndBlankLines() {
        val path = tmp.root.toPath().resolve("zones.txt")
        Files.writeString(
            path,
            """
            # comment line
            Europe/Paris

            Asia/Singapore
            """.trimIndent(),
        )
        val cfg = UserConfig(path)
        assertEquals(
            listOf(ZoneId.of("Europe/Paris"), ZoneId.of("Asia/Singapore")),
            cfg.targetZones(),
        )
    }

    @Test fun fallsBackToDefaultsWhenFileHasOnlyJunk() {
        val path = tmp.root.toPath().resolve("zones.txt")
        Files.writeString(path, "Not/A/Real/Zone\nAlso_Garbage\n")
        val cfg = UserConfig(path)
        // Junk-only file → defaults so the app keeps working.
        assertTrue(cfg.targetZones().any { it.id == "America/New_York" })
    }

    /** Removing the last zone is a deliberate choice, not "unset" — the defaults must
     *  not silently reappear. */
    @Test fun emptyListPersistsAsEmptyRatherThanReviveDefaults() {
        val cfg = UserConfig(tmp.root.toPath().resolve("zones.txt"))
        cfg.setTargetZones(listOf(ZoneId.of("Europe/London")))
        cfg.setTargetZones(emptyList())
        assertEquals(emptyList<ZoneId>(), cfg.targetZones())
    }

    /** A hand-written file with no zones in it is genuinely ambiguous, so it keeps the
     *  forgiving behaviour. Only app-written files are trusted verbatim. */
    @Test fun handWrittenEmptyFileStillFallsBackToDefaults() {
        val path = tmp.root.toPath().resolve("zones.txt")
        Files.writeString(path, "# just a comment\n\n")
        assertTrue(UserConfig(path).targetZones().any { it.id == "America/New_York" })
    }

    @Test fun writeFailureIsReportedNotThrown() {
        // Parent exists as a *file*, so createDirectories/writeString cannot succeed.
        val blocker = tmp.root.toPath().resolve("blocked")
        Files.writeString(blocker, "not a directory")
        val cfg = UserConfig(blocker.resolve("zones.txt"))
        assertTrue(
            "a failed write must come back as Result.failure, not an exception",
            cfg.setTargetZones(listOf(ZoneId.of("Asia/Tokyo"))).isFailure,
        )
    }
}
