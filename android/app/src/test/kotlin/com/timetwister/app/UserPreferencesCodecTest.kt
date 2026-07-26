package com.timetwister.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The zone list's serialisation and defaults, which are the only parts of UserPreferences
 * that do not need a Context. Everything here was previously untested — `:app` had no test
 * source set at all, so CI's :app:testDebugUnitTest step passed without executing anything.
 */
class UserPreferencesCodecTest {

    private fun ids(vararg zones: ZoneId) = zones.map { it.id }

    // ---- encode / decode round trip ----

    @Test
    fun `encode then decode preserves order`() {
        val zones = listOf(
            ZoneId.of("America/New_York"),
            ZoneId.of("Asia/Kolkata"),
            ZoneId.of("Europe/London"),
        )
        // Order is not incidental: it is the order the stamp renders in.
        assertEquals(zones, UserPreferences.decode(UserPreferences.encode(zones)))
    }

    @Test
    fun `encode uses a comma separated list of IANA ids`() {
        assertEquals(
            "America/Chicago,Europe/Paris",
            UserPreferences.encode(listOf(ZoneId.of("America/Chicago"), ZoneId.of("Europe/Paris"))),
        )
    }

    // ---- the three states decode distinguishes ----

    @Test
    fun `null means never configured and yields the defaults`() {
        assertEquals(UserPreferences.defaults, UserPreferences.decode(null))
    }

    @Test
    fun `empty string means the user removed every zone and is honoured`() {
        // The bug this pins: collapsing "" into "unset" resurrected all the defaults,
        // so deleting your last zone read as the app ignoring you.
        assertEquals(emptyList<ZoneId>(), UserPreferences.decode(""))
    }

    @Test
    fun `a stored list is returned as stored`() {
        assertEquals(
            listOf(ZoneId.of("America/Denver")),
            UserPreferences.decode("America/Denver"),
        )
    }

    // ---- corrupt / hostile stored values ----

    @Test
    fun `an unknown zone id is dropped rather than throwing`() {
        // A tzdb update can retire an id that is sitting in someone's saved list. Losing
        // one zone is recoverable; ZoneId.of throwing out of every read is not.
        assertEquals(
            listOf(ZoneId.of("America/New_York")),
            UserPreferences.decode("America/New_York,Mars/Olympus_Mons"),
        )
    }

    @Test
    fun `blank entries and stray separators are ignored`() {
        assertEquals(
            listOf(ZoneId.of("Europe/Berlin")),
            UserPreferences.decode(",,Europe/Berlin,, ,"),
        )
    }

    @Test
    fun `an entirely unparseable value decodes to empty, not to defaults`() {
        // Distinct from null on purpose: this string IS a configuration, just a broken
        // one, and silently substituting five default zones would hide the breakage.
        assertEquals(emptyList<ZoneId>(), UserPreferences.decode("!!!,???"))
    }

    // ---- defaults, at zones other than this JVM's ----

    @Test
    fun `a US default list dedupes the user's own zone`() {
        val defaults = UserPreferences.computeDefaults(ZoneId.of("America/New_York"))
        assertEquals(
            ids(
                ZoneId.of("America/New_York"),
                ZoneId.of("America/Chicago"),
                ZoneId.of("America/Denver"),
                ZoneId.of("America/Los_Angeles"),
            ),
            defaults.map { it.id },
        )
    }

    @Test
    fun `defaults never exceed the recommended maximum`() {
        // The regression that motivated computeDefaults: "your zone + four US zones" is
        // five entries for most of the world, and five is over the soft cap, so a fresh
        // install opened settings already showing the red "too many zones" warning.
        val zonesWorthChecking = listOf(
            "Europe/London", "Europe/Berlin", "Asia/Kolkata", "Asia/Tokyo",
            "Australia/Sydney", "America/Sao_Paulo", "Africa/Lagos", "UTC",
            // Not outside the US, and the reason this was easy to miss from a US desk:
            "America/Phoenix", "America/Anchorage", "Pacific/Honolulu",
            // ...and the four that dedupe:
            "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
        )
        for (id in zonesWorthChecking) {
            val defaults = UserPreferences.computeDefaults(ZoneId.of(id))
            assertTrue(
                "defaults at $id were ${defaults.size} zones: ${defaults.map { it.id }}",
                defaults.size <= UserPreferences.MAX_RECOMMENDED_ZONES,
            )
        }
    }

    @Test
    fun `defaults always lead with the user's own zone`() {
        for (id in listOf("Europe/London", "America/Phoenix", "Asia/Tokyo", "America/Chicago")) {
            val defaults = UserPreferences.computeDefaults(ZoneId.of(id))
            assertEquals(id, defaults.first().id)
        }
    }

    @Test
    fun `a non US user keeps Eastern Central and Pacific, and loses Mountain`() {
        // Which one gets dropped is a judgement call, so pin it: Mountain is the least
        // populous US zone, and anyone who wants it is usually in it.
        val defaults = UserPreferences.computeDefaults(ZoneId.of("Europe/London")).map { it.id }
        assertEquals(
            listOf(
                "Europe/London",
                "America/New_York",
                "America/Chicago",
                "America/Los_Angeles",
            ),
            defaults,
        )
    }

    @Test
    fun `a Mountain user keeps Mountain because it is their own zone`() {
        val defaults = UserPreferences.computeDefaults(ZoneId.of("America/Denver")).map { it.id }
        assertTrue(defaults.toString(), "America/Denver" in defaults)
        assertEquals(4, defaults.size)
    }

    @Test
    fun `defaults contain no duplicates at any zone`() {
        for (id in ZoneId.getAvailableZoneIds().sorted().filter { it.startsWith("America/") }) {
            val defaults = UserPreferences.computeDefaults(ZoneId.of(id))
            assertEquals(
                "duplicate in defaults at $id: ${defaults.map { z -> z.id }}",
                defaults.map { z -> z.id }.distinct().size,
                defaults.size,
            )
        }
    }
}
