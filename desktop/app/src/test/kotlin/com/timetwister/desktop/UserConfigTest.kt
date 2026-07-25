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
}
