package com.timetwister.core

import java.time.ZoneId

/**
 * Maps E.164 phone-number country codes to a canonical IANA timezone.
 *
 * Used by the Android build to suggest target zones from the user's contact list
 * (see [com.timetwister.app.ContactZoneInferencer]). Kept in :core so the lookup
 * is pure JVM and JVM-testable — and so a future desktop port can reuse it.
 *
 * For countries spanning multiple zones (US, RU, BR, AU, CA) the canonical zone is
 * the *most populated* — the user can always adjust the suggestion before saving.
 * No third-party phone-parser dependency: we walk the digit string and match the
 * longest 1–3 digit prefix from the ITU E.164 table, which is enough for the
 * "what zones do my contacts live in" use case. Misses on ambiguous +1 numbers
 * (NANP — US/CA/Caribbean) fall back to America/New_York, which is the safest
 * default for the dominant block.
 */
object CountryZone {

    /**
     * Extract the IANA ZoneId for a contact phone number, or null if we can't
     * pin one down (no country code, garbled, or an unmapped country).
     *
     * Accepts "+44 7700 900123", "0044-7700-900123", "+1 (555) 123-4567" etc.
     * A bare national-format number with no country prefix returns null —
     * we don't guess the user's own zone here; that's the system default.
     */
    fun zoneForPhone(raw: String): ZoneId? {
        val digits = raw.trim()
            .removePrefix("00")
            .let { if (it.startsWith("+")) it.drop(1) else if (raw.startsWith("00")) it else return null }
            .filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // Try longest prefix first so e.g. "972" (Israel) wins over "97" (no such code).
        for (len in 3 downTo 1) {
            if (digits.length < len) continue
            val code = digits.substring(0, len)
            zoneByDialingCode[code]?.let { return runCatching { ZoneId.of(it) }.getOrNull() }
        }
        return null
    }

    /**
     * Direct lookup for callers that already have an ISO-3166-1 alpha-2 code
     * (e.g. from a country picker). Returns null for unmapped countries.
     */
    fun zoneForCountry(iso2: String): ZoneId? =
        zoneByIso2[iso2.uppercase()]?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    // ITU E.164 dialing code → IANA zone. Where a code spans multiple countries
    // (NANP +1, Kazakhstan/Russia +7), the canonical entry is the dominant member.
    private val zoneByDialingCode: Map<String, String> = mapOf(
        "1" to "America/New_York",          // NANP — US default
        "7" to "Europe/Moscow",             // RU/KZ — RU dominant
        "20" to "Africa/Cairo",
        "27" to "Africa/Johannesburg",
        "30" to "Europe/Athens",
        "31" to "Europe/Amsterdam",
        "32" to "Europe/Brussels",
        "33" to "Europe/Paris",
        "34" to "Europe/Madrid",
        "36" to "Europe/Budapest",
        "39" to "Europe/Rome",
        "40" to "Europe/Bucharest",
        "41" to "Europe/Zurich",
        "43" to "Europe/Vienna",
        "44" to "Europe/London",
        "45" to "Europe/Copenhagen",
        "46" to "Europe/Stockholm",
        "47" to "Europe/Oslo",
        "48" to "Europe/Warsaw",
        "49" to "Europe/Berlin",
        "51" to "America/Lima",
        "52" to "America/Mexico_City",
        "53" to "America/Havana",
        "54" to "America/Argentina/Buenos_Aires",
        "55" to "America/Sao_Paulo",
        "56" to "America/Santiago",
        "57" to "America/Bogota",
        "58" to "America/Caracas",
        "60" to "Asia/Kuala_Lumpur",
        "61" to "Australia/Sydney",
        "62" to "Asia/Jakarta",
        "63" to "Asia/Manila",
        "64" to "Pacific/Auckland",
        "65" to "Asia/Singapore",
        "66" to "Asia/Bangkok",
        "81" to "Asia/Tokyo",
        "82" to "Asia/Seoul",
        "84" to "Asia/Ho_Chi_Minh",
        "86" to "Asia/Shanghai",
        "90" to "Europe/Istanbul",
        "91" to "Asia/Kolkata",
        "92" to "Asia/Karachi",
        "93" to "Asia/Kabul",
        "94" to "Asia/Colombo",
        "95" to "Asia/Yangon",
        "98" to "Asia/Tehran",
        "212" to "Africa/Casablanca",
        "213" to "Africa/Algiers",
        "216" to "Africa/Tunis",
        "218" to "Africa/Tripoli",
        "220" to "Africa/Banjul",
        "221" to "Africa/Dakar",
        "222" to "Africa/Nouakchott",
        "223" to "Africa/Bamako",
        "224" to "Africa/Conakry",
        "225" to "Africa/Abidjan",
        "226" to "Africa/Ouagadougou",
        "227" to "Africa/Niamey",
        "228" to "Africa/Lome",
        "229" to "Africa/Porto-Novo",
        "230" to "Indian/Mauritius",
        "231" to "Africa/Monrovia",
        "232" to "Africa/Freetown",
        "233" to "Africa/Accra",
        "234" to "Africa/Lagos",
        "235" to "Africa/Ndjamena",
        "236" to "Africa/Bangui",
        "237" to "Africa/Douala",
        "238" to "Atlantic/Cape_Verde",
        "239" to "Africa/Sao_Tome",
        "240" to "Africa/Malabo",
        "241" to "Africa/Libreville",
        "242" to "Africa/Brazzaville",
        "243" to "Africa/Kinshasa",
        "244" to "Africa/Luanda",
        "245" to "Africa/Bissau",
        "248" to "Indian/Mahe",
        "249" to "Africa/Khartoum",
        "250" to "Africa/Kigali",
        "251" to "Africa/Addis_Ababa",
        "252" to "Africa/Mogadishu",
        "253" to "Africa/Djibouti",
        "254" to "Africa/Nairobi",
        "255" to "Africa/Dar_es_Salaam",
        "256" to "Africa/Kampala",
        "257" to "Africa/Bujumbura",
        "258" to "Africa/Maputo",
        "260" to "Africa/Lusaka",
        "261" to "Indian/Antananarivo",
        "262" to "Indian/Reunion",
        "263" to "Africa/Harare",
        "264" to "Africa/Windhoek",
        "265" to "Africa/Blantyre",
        "266" to "Africa/Maseru",
        "267" to "Africa/Gaborone",
        "268" to "Africa/Mbabane",
        "269" to "Indian/Comoro",
        "291" to "Africa/Asmara",
        "297" to "America/Aruba",
        "298" to "Atlantic/Faroe",
        "299" to "America/Godthab",
        "350" to "Europe/Gibraltar",
        "351" to "Europe/Lisbon",
        "352" to "Europe/Luxembourg",
        "353" to "Europe/Dublin",
        "354" to "Atlantic/Reykjavik",
        "355" to "Europe/Tirane",
        "356" to "Europe/Malta",
        "357" to "Asia/Nicosia",
        "358" to "Europe/Helsinki",
        "359" to "Europe/Sofia",
        "370" to "Europe/Vilnius",
        "371" to "Europe/Riga",
        "372" to "Europe/Tallinn",
        "373" to "Europe/Chisinau",
        "374" to "Asia/Yerevan",
        "375" to "Europe/Minsk",
        "376" to "Europe/Andorra",
        "377" to "Europe/Monaco",
        "378" to "Europe/San_Marino",
        "380" to "Europe/Kiev",
        "381" to "Europe/Belgrade",
        "382" to "Europe/Podgorica",
        "385" to "Europe/Zagreb",
        "386" to "Europe/Ljubljana",
        "387" to "Europe/Sarajevo",
        "389" to "Europe/Skopje",
        "420" to "Europe/Prague",
        "421" to "Europe/Bratislava",
        "423" to "Europe/Vaduz",
        "501" to "America/Belize",
        "502" to "America/Guatemala",
        "503" to "America/El_Salvador",
        "504" to "America/Tegucigalpa",
        "505" to "America/Managua",
        "506" to "America/Costa_Rica",
        "507" to "America/Panama",
        "509" to "America/Port-au-Prince",
        "591" to "America/La_Paz",
        "592" to "America/Guyana",
        "593" to "America/Guayaquil",
        "595" to "America/Asuncion",
        "598" to "America/Montevideo",
        "599" to "America/Curacao",
        "670" to "Asia/Dili",
        "673" to "Asia/Brunei",
        "674" to "Pacific/Nauru",
        "675" to "Pacific/Port_Moresby",
        "676" to "Pacific/Tongatapu",
        "677" to "Pacific/Guadalcanal",
        "678" to "Pacific/Efate",
        "679" to "Pacific/Fiji",
        "680" to "Pacific/Palau",
        "685" to "Pacific/Apia",
        "686" to "Pacific/Tarawa",
        "687" to "Pacific/Noumea",
        "688" to "Pacific/Funafuti",
        "689" to "Pacific/Tahiti",
        "691" to "Pacific/Pohnpei",
        "692" to "Pacific/Majuro",
        "850" to "Asia/Pyongyang",
        "852" to "Asia/Hong_Kong",
        "853" to "Asia/Macau",
        "855" to "Asia/Phnom_Penh",
        "856" to "Asia/Vientiane",
        "880" to "Asia/Dhaka",
        "886" to "Asia/Taipei",
        "960" to "Indian/Maldives",
        "961" to "Asia/Beirut",
        "962" to "Asia/Amman",
        "963" to "Asia/Damascus",
        "964" to "Asia/Baghdad",
        "965" to "Asia/Kuwait",
        "966" to "Asia/Riyadh",
        "967" to "Asia/Aden",
        "968" to "Asia/Muscat",
        "970" to "Asia/Gaza",
        "971" to "Asia/Dubai",
        "972" to "Asia/Jerusalem",
        "973" to "Asia/Bahrain",
        "974" to "Asia/Qatar",
        "975" to "Asia/Thimphu",
        "976" to "Asia/Ulaanbaatar",
        "977" to "Asia/Kathmandu",
        "992" to "Asia/Dushanbe",
        "993" to "Asia/Ashgabat",
        "994" to "Asia/Baku",
        "995" to "Asia/Tbilisi",
        "996" to "Asia/Bishkek",
        "998" to "Asia/Tashkent",
    )

    private val zoneByIso2: Map<String, String> = mapOf(
        "US" to "America/New_York", "CA" to "America/Toronto", "GB" to "Europe/London",
        "FR" to "Europe/Paris", "DE" to "Europe/Berlin", "ES" to "Europe/Madrid",
        "IT" to "Europe/Rome", "NL" to "Europe/Amsterdam", "BE" to "Europe/Brussels",
        "CH" to "Europe/Zurich", "AT" to "Europe/Vienna", "SE" to "Europe/Stockholm",
        "NO" to "Europe/Oslo", "DK" to "Europe/Copenhagen", "FI" to "Europe/Helsinki",
        "PL" to "Europe/Warsaw", "IE" to "Europe/Dublin", "PT" to "Europe/Lisbon",
        "GR" to "Europe/Athens", "RU" to "Europe/Moscow", "UA" to "Europe/Kiev",
        "TR" to "Europe/Istanbul", "JP" to "Asia/Tokyo", "KR" to "Asia/Seoul",
        "CN" to "Asia/Shanghai", "TW" to "Asia/Taipei", "HK" to "Asia/Hong_Kong",
        "SG" to "Asia/Singapore", "IN" to "Asia/Kolkata", "PK" to "Asia/Karachi",
        "BD" to "Asia/Dhaka", "TH" to "Asia/Bangkok", "VN" to "Asia/Ho_Chi_Minh",
        "ID" to "Asia/Jakarta", "MY" to "Asia/Kuala_Lumpur", "PH" to "Asia/Manila",
        "AU" to "Australia/Sydney", "NZ" to "Pacific/Auckland", "ZA" to "Africa/Johannesburg",
        "EG" to "Africa/Cairo", "NG" to "Africa/Lagos", "KE" to "Africa/Nairobi",
        "MX" to "America/Mexico_City", "BR" to "America/Sao_Paulo", "AR" to "America/Argentina/Buenos_Aires",
        "CL" to "America/Santiago", "CO" to "America/Bogota", "PE" to "America/Lima",
        "AE" to "Asia/Dubai", "SA" to "Asia/Riyadh", "IL" to "Asia/Jerusalem",
        "QA" to "Asia/Qatar", "KW" to "Asia/Kuwait", "BH" to "Asia/Bahrain",
    )
}
