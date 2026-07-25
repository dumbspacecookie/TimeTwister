//
//  CountryZone.swift
//  TimeTwisterCore
//
//  Phone-number → canonical IANA timezone. Mirrors com.timetwister.core.CountryZone
//  on the Kotlin side so the two platforms produce identical suggestions for the
//  same contact list. Used by the iOS Action Extension (and any future iOS
//  contact-import flow) to suggest target zones without a third-party parser.
//

import Foundation

public enum CountryZone {

    /// Extract the IANA TimeZone for a contact phone number, or nil if we can't
    /// pin one down (no country prefix, garbled, or unmapped country).
    /// Accepts "+44 7700 900123", "0044-7700-900123", "+1 (555) 123-4567" etc.
    public static func zone(forPhone raw: String) -> TimeZone? {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        let hadPlus = trimmed.hasPrefix("+")
        let hadDoubleZero = trimmed.hasPrefix("00")
        guard hadPlus || hadDoubleZero else { return nil }

        // Strip prefix sigil, then keep only digits.
        var working: String = trimmed
        if hadPlus {
            working = String(working.dropFirst())
        } else if hadDoubleZero {
            working = String(working.dropFirst(2))
        }
        let digits = working.filter { $0.isNumber }
        guard !digits.isEmpty else { return nil }

        // Longest prefix wins so e.g. "972" (Israel) beats "97" (no such code).
        for len in stride(from: 3, through: 1, by: -1) {
            guard digits.count >= len else { continue }
            let code = String(digits.prefix(len))
            if let id = zoneByDialingCode[code], let tz = TimeZone(identifier: id) {
                return tz
            }
        }
        return nil
    }

    /// Direct lookup for callers that already have an ISO-3166-1 alpha-2 code.
    public static func zone(forCountry iso2: String) -> TimeZone? {
        guard let id = zoneByIso2[iso2.uppercased()] else { return nil }
        return TimeZone(identifier: id)
    }

    // Mirror of the Kotlin table. Where a code spans multiple countries (NANP +1,
    // Kazakhstan/Russia +7) the canonical entry is the dominant member.
    private static let zoneByDialingCode: [String: String] = [
        "1": "America/New_York", "7": "Europe/Moscow",
        "20": "Africa/Cairo", "27": "Africa/Johannesburg",
        "30": "Europe/Athens", "31": "Europe/Amsterdam", "32": "Europe/Brussels",
        "33": "Europe/Paris", "34": "Europe/Madrid", "36": "Europe/Budapest",
        "39": "Europe/Rome", "40": "Europe/Bucharest", "41": "Europe/Zurich",
        "43": "Europe/Vienna", "44": "Europe/London", "45": "Europe/Copenhagen",
        "46": "Europe/Stockholm", "47": "Europe/Oslo", "48": "Europe/Warsaw",
        "49": "Europe/Berlin",
        "51": "America/Lima", "52": "America/Mexico_City", "53": "America/Havana",
        "54": "America/Argentina/Buenos_Aires", "55": "America/Sao_Paulo",
        "56": "America/Santiago", "57": "America/Bogota", "58": "America/Caracas",
        "60": "Asia/Kuala_Lumpur", "61": "Australia/Sydney", "62": "Asia/Jakarta",
        "63": "Asia/Manila", "64": "Pacific/Auckland", "65": "Asia/Singapore",
        "66": "Asia/Bangkok",
        "81": "Asia/Tokyo", "82": "Asia/Seoul", "84": "Asia/Ho_Chi_Minh",
        "86": "Asia/Shanghai",
        "90": "Europe/Istanbul", "91": "Asia/Kolkata", "92": "Asia/Karachi",
        "93": "Asia/Kabul", "94": "Asia/Colombo", "95": "Asia/Yangon",
        "98": "Asia/Tehran",
        "212": "Africa/Casablanca", "213": "Africa/Algiers", "216": "Africa/Tunis",
        "218": "Africa/Tripoli", "220": "Africa/Banjul", "221": "Africa/Dakar",
        "222": "Africa/Nouakchott", "223": "Africa/Bamako", "224": "Africa/Conakry",
        "225": "Africa/Abidjan", "226": "Africa/Ouagadougou", "227": "Africa/Niamey",
        "228": "Africa/Lome", "229": "Africa/Porto-Novo", "230": "Indian/Mauritius",
        "231": "Africa/Monrovia", "232": "Africa/Freetown", "233": "Africa/Accra",
        "234": "Africa/Lagos", "235": "Africa/Ndjamena", "236": "Africa/Bangui",
        "237": "Africa/Douala", "238": "Atlantic/Cape_Verde", "239": "Africa/Sao_Tome",
        "240": "Africa/Malabo", "241": "Africa/Libreville", "242": "Africa/Brazzaville",
        "243": "Africa/Kinshasa", "244": "Africa/Luanda", "245": "Africa/Bissau",
        "248": "Indian/Mahe", "249": "Africa/Khartoum", "250": "Africa/Kigali",
        "251": "Africa/Addis_Ababa", "252": "Africa/Mogadishu", "253": "Africa/Djibouti",
        "254": "Africa/Nairobi", "255": "Africa/Dar_es_Salaam", "256": "Africa/Kampala",
        "257": "Africa/Bujumbura", "258": "Africa/Maputo", "260": "Africa/Lusaka",
        "261": "Indian/Antananarivo", "262": "Indian/Reunion", "263": "Africa/Harare",
        "264": "Africa/Windhoek", "265": "Africa/Blantyre", "266": "Africa/Maseru",
        "267": "Africa/Gaborone", "268": "Africa/Mbabane", "269": "Indian/Comoro",
        "291": "Africa/Asmara", "297": "America/Aruba", "298": "Atlantic/Faroe",
        "299": "America/Godthab",
        "350": "Europe/Gibraltar", "351": "Europe/Lisbon", "352": "Europe/Luxembourg",
        "353": "Europe/Dublin", "354": "Atlantic/Reykjavik", "355": "Europe/Tirane",
        "356": "Europe/Malta", "357": "Asia/Nicosia", "358": "Europe/Helsinki",
        "359": "Europe/Sofia", "370": "Europe/Vilnius", "371": "Europe/Riga",
        "372": "Europe/Tallinn", "373": "Europe/Chisinau", "374": "Asia/Yerevan",
        "375": "Europe/Minsk", "376": "Europe/Andorra", "377": "Europe/Monaco",
        "378": "Europe/San_Marino", "380": "Europe/Kiev", "381": "Europe/Belgrade",
        "382": "Europe/Podgorica", "385": "Europe/Zagreb", "386": "Europe/Ljubljana",
        "387": "Europe/Sarajevo", "389": "Europe/Skopje",
        "420": "Europe/Prague", "421": "Europe/Bratislava", "423": "Europe/Vaduz",
        "501": "America/Belize", "502": "America/Guatemala", "503": "America/El_Salvador",
        "504": "America/Tegucigalpa", "505": "America/Managua", "506": "America/Costa_Rica",
        "507": "America/Panama", "509": "America/Port-au-Prince",
        "591": "America/La_Paz", "592": "America/Guyana", "593": "America/Guayaquil",
        "595": "America/Asuncion", "598": "America/Montevideo", "599": "America/Curacao",
        "670": "Asia/Dili", "673": "Asia/Brunei", "674": "Pacific/Nauru",
        "675": "Pacific/Port_Moresby", "676": "Pacific/Tongatapu",
        "677": "Pacific/Guadalcanal", "678": "Pacific/Efate", "679": "Pacific/Fiji",
        "680": "Pacific/Palau", "685": "Pacific/Apia", "686": "Pacific/Tarawa",
        "687": "Pacific/Noumea", "688": "Pacific/Funafuti", "689": "Pacific/Tahiti",
        "691": "Pacific/Pohnpei", "692": "Pacific/Majuro",
        "850": "Asia/Pyongyang", "852": "Asia/Hong_Kong", "853": "Asia/Macau",
        "855": "Asia/Phnom_Penh", "856": "Asia/Vientiane", "880": "Asia/Dhaka",
        "886": "Asia/Taipei",
        "960": "Indian/Maldives", "961": "Asia/Beirut", "962": "Asia/Amman",
        "963": "Asia/Damascus", "964": "Asia/Baghdad", "965": "Asia/Kuwait",
        "966": "Asia/Riyadh", "967": "Asia/Aden", "968": "Asia/Muscat",
        "970": "Asia/Gaza", "971": "Asia/Dubai", "972": "Asia/Jerusalem",
        "973": "Asia/Bahrain", "974": "Asia/Qatar", "975": "Asia/Thimphu",
        "976": "Asia/Ulaanbaatar", "977": "Asia/Kathmandu", "992": "Asia/Dushanbe",
        "993": "Asia/Ashgabat", "994": "Asia/Baku", "995": "Asia/Tbilisi",
        "996": "Asia/Bishkek", "998": "Asia/Tashkent",
    ]

    private static let zoneByIso2: [String: String] = [
        "US": "America/New_York", "CA": "America/Toronto", "GB": "Europe/London",
        "FR": "Europe/Paris", "DE": "Europe/Berlin", "ES": "Europe/Madrid",
        "IT": "Europe/Rome", "NL": "Europe/Amsterdam", "BE": "Europe/Brussels",
        "CH": "Europe/Zurich", "AT": "Europe/Vienna", "SE": "Europe/Stockholm",
        "NO": "Europe/Oslo", "DK": "Europe/Copenhagen", "FI": "Europe/Helsinki",
        "PL": "Europe/Warsaw", "IE": "Europe/Dublin", "PT": "Europe/Lisbon",
        "GR": "Europe/Athens", "RU": "Europe/Moscow", "UA": "Europe/Kiev",
        "TR": "Europe/Istanbul", "JP": "Asia/Tokyo", "KR": "Asia/Seoul",
        "CN": "Asia/Shanghai", "TW": "Asia/Taipei", "HK": "Asia/Hong_Kong",
        "SG": "Asia/Singapore", "IN": "Asia/Kolkata", "PK": "Asia/Karachi",
        "BD": "Asia/Dhaka", "TH": "Asia/Bangkok", "VN": "Asia/Ho_Chi_Minh",
        "ID": "Asia/Jakarta", "MY": "Asia/Kuala_Lumpur", "PH": "Asia/Manila",
        "AU": "Australia/Sydney", "NZ": "Pacific/Auckland", "ZA": "Africa/Johannesburg",
        "EG": "Africa/Cairo", "NG": "Africa/Lagos", "KE": "Africa/Nairobi",
        "MX": "America/Mexico_City", "BR": "America/Sao_Paulo",
        "AR": "America/Argentina/Buenos_Aires", "CL": "America/Santiago",
        "CO": "America/Bogota", "PE": "America/Lima", "AE": "Asia/Dubai",
        "SA": "Asia/Riyadh", "IL": "Asia/Jerusalem", "QA": "Asia/Qatar",
        "KW": "Asia/Kuwait", "BH": "Asia/Bahrain",
    ]
}
