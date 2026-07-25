package com.timetwister.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class CountryZoneTest {

    @Test fun plusPrefixedNumberResolvesToZone() {
        assertEquals(ZoneId.of("Europe/London"), CountryZone.zoneForPhone("+44 7700 900123"))
        assertEquals(ZoneId.of("Asia/Tokyo"), CountryZone.zoneForPhone("+81 3 1234 5678"))
        assertEquals(ZoneId.of("Asia/Jerusalem"), CountryZone.zoneForPhone("+972 50 123 4567"))
    }

    @Test fun zeroZeroPrefixedNumberResolves() {
        assertEquals(ZoneId.of("Europe/Paris"), CountryZone.zoneForPhone("0033 6 12 34 56 78"))
    }

    @Test fun nanpDefaultsToUsEastern() {
        // Caribbean and Canada also live in +1, but US Eastern is the safest dominant default.
        // User can refine the suggestion before saving.
        assertEquals(ZoneId.of("America/New_York"), CountryZone.zoneForPhone("+1 (555) 123-4567"))
    }

    @Test fun longestPrefixWinsOverShorter() {
        // "+1" (NANP) is in the table; "+12" is not. Without longest-prefix-wins we'd
        // misroute every 12-prefixed code to "+1".
        // (This case is hypothetical — there's no real +12X country today — but the
        // logic still needs to prefer 3-digit matches when they exist.)
        assertEquals(ZoneId.of("Asia/Jerusalem"), CountryZone.zoneForPhone("+972..."))
        assertEquals(ZoneId.of("Europe/Belgrade"), CountryZone.zoneForPhone("+381 64 1234567"))
    }

    @Test fun bareNationalNumberReturnsNull() {
        // No country prefix → we don't guess the user's own zone here.
        assertNull(CountryZone.zoneForPhone("555-1234"))
        assertNull(CountryZone.zoneForPhone("07700 900123"))
    }

    @Test fun unmappedOrGarbledNumberReturnsNull() {
        assertNull(CountryZone.zoneForPhone(""))
        assertNull(CountryZone.zoneForPhone("+"))
        assertNull(CountryZone.zoneForPhone("+999 9 12345"))
    }

    @Test fun iso2LookupHandlesCommonCountries() {
        assertEquals(ZoneId.of("America/Toronto"), CountryZone.zoneForCountry("CA"))
        assertEquals(ZoneId.of("Asia/Kolkata"), CountryZone.zoneForCountry("in"))
        assertNull(CountryZone.zoneForCountry("ZZ"))
    }
}
