//
//  TimeZoneAliasTests.swift
//  TimeTwisterCoreTests
//
//  Covers the label contract, which is the part of this file that reaches a
//  reader's screen. Mirrors the Kotlin core's expectations.
//
//  These assertions are deliberately written against *invariants* rather than
//  exact abbreviation strings wherever the platform gets a say. Apple's
//  Foundation and swift-corelibs disagree about what `abbreviation(for:)`
//  returns for some zones, and a test that pins one spelling would go green here
//  and red on a Mac — which would make this suite worse than useless as a
//  cross-platform oracle.
//

import XCTest
@testable import TimeTwisterCore

final class TimeZoneAliasTests: XCTestCase {

    // Two instants that straddle DST in the northern hemisphere.
    private let january = Date(timeIntervalSince1970: 1_768_478_400)  // 2026-01-15T12:00Z
    private let july = Date(timeIntervalSince1970: 1_784_030_400)     // 2026-07-15T12:00Z

    private func zone(_ id: String) throws -> TimeZone {
        try XCTUnwrap(TimeZone(identifier: id), "no such zone: \(id)")
    }

    // MARK: - the headline contract

    /// A raw IANA identifier must never reach a rendered stamp. "5pm
    /// America/Toronto" is not something anyone wants to send, and before the
    /// offset fallback existed that is exactly what every Canadian, Berliner,
    /// Dubliner and Australian outside the hardcoded list got.
    func testShortLabelNeverReturnsRawIanaIdentifier() throws {
        let unlisted = [
            "America/Toronto", "America/Vancouver", "Europe/Dublin",
            "Europe/Berlin", "Europe/Lisbon", "Australia/Perth",
            "America/Sao_Paulo", "Africa/Nairobi", "Asia/Kathmandu",
            "Asia/Tehran", "Pacific/Chatham", "America/St_Johns",
        ]
        for id in unlisted {
            let tz = try zone(id)
            for at in [january, july, nil] as [Date?] {
                let label = TimeZoneAlias.shortLabel(for: tz, at: at)
                XCTAssertFalse(label.contains("/"), "\(id) leaked an IANA id: \(label)")
                XCTAssertNotEqual(label, id)
                XCTAssertFalse(label.isEmpty, "\(id) produced an empty label")
            }
        }
    }

    /// An unlisted zone gets our own "UTC±h[:mm]" form, exactly, on every platform.
    ///
    /// This was previously written as "a word-shaped abbreviation OR our offset
    /// form", to accommodate `TimeZone.abbreviation(for:)` answering "CEST" on
    /// Apple's Foundation and an offset-shaped stand-in on swift-corelibs. That
    /// disjunction was the bug: a contract loose enough to accept both spellings
    /// certified a core that rendered "11pm CEST" on a phone and "11pm UTC+2" on
    /// CI, and a differential run later found all 20 tested unlisted zones
    /// diverging on exactly this call. The fix was to stop asking the platform at
    /// all, which means the answer is now single-valued and the test can say so.
    func testUnlistedZonesGetOurOwnOffsetForm() throws {
        let expected = [
            "Asia/Kathmandu": "UTC+5:45",
            "Asia/Tehran": "UTC+3:30",
            "Pacific/Marquesas": "UTC-9:30",
            "Europe/Berlin": "UTC+1",
            "America/Toronto": "UTC-5",
            "Asia/Dubai": "UTC+4",
            "Europe/Dublin": "UTC",
        ]
        for (id, label) in expected {
            XCTAssertEqual(
                TimeZoneAlias.shortLabel(for: try zone(id), at: january), label, id
            )
        }
    }

    /// Europe/Dublin is the specific trap worth naming: its summer abbreviation is
    /// "IST", and `map["ist"]` is Asia/Kolkata. Emitting the platform's word there
    /// labelled a Dublin user's time in a way that re-reads four and a half hours
    /// away, on iOS only — invisible to every test run off a Mac.
    func testDublinIsNeverLabelledAsIndianStandardTime() throws {
        let dublin = try zone("Europe/Dublin")
        for at in [january, july] {
            let label = TimeZoneAlias.shortLabel(for: dublin, at: at)
            XCTAssertNotEqual(label, "IST", "Dublin must not borrow India's abbreviation")
            let back = try XCTUnwrap(TimeZoneAlias.resolve(label))
            XCTAssertEqual(
                back.secondsFromGMT(for: at), dublin.secondsFromGMT(for: at),
                "label '\(label)' does not read back to Dublin's offset"
            )
        }
    }

    // MARK: - region labels are DST-stable

    /// "ET" is correct whether New York is on EST or EDT. These are hardcoded
    /// precisely so the label does not flip twice a year for the zones people
    /// write most often.
    func testRegionLabelsDoNotChangeAcrossDst() throws {
        let expected = [
            "America/New_York": "ET",
            "America/Chicago": "CT",
            "America/Denver": "MT",
            "America/Los_Angeles": "PT",
            "Europe/London": "UK",
            "Asia/Kolkata": "IST",
            "Asia/Tokyo": "JST",
        ]
        for (id, label) in expected {
            let tz = try zone(id)
            XCTAssertEqual(TimeZoneAlias.shortLabel(for: tz, at: january), label, id)
            XCTAssertEqual(TimeZoneAlias.shortLabel(for: tz, at: july), label, id)
            XCTAssertEqual(TimeZoneAlias.shortLabel(for: tz), label, id)
        }
    }

    /// UTC survives whichever way the platform canonicalises "UTC" <-> "GMT".
    func testUtcLabelsAsUtcWhicheverNameThePlatformKeeps() throws {
        for id in ["UTC", "GMT"] {
            guard let tz = TimeZone(identifier: id) else { continue }
            XCTAssertEqual(TimeZoneAlias.shortLabel(for: tz, at: january), "UTC")
            XCTAssertEqual(TimeZoneAlias.shortLabel(for: tz), "UTC")
        }
    }

    /// The mirror image: zones whose conventional abbreviation *is* DST-specific
    /// are deliberately not pinned, so their label has to track the instant. A
    /// fixed "CET" in July is wrong by an hour for anyone who trusts it.
    func testDstSpecificZonesTrackTheInstant() throws {
        for id in ["Europe/Paris", "Europe/Helsinki", "Australia/Sydney", "Pacific/Auckland"] {
            let tz = try zone(id)
            let winter = TimeZoneAlias.shortLabel(for: tz, at: january)
            let summer = TimeZoneAlias.shortLabel(for: tz, at: july)
            XCTAssertNotEqual(winter, summer, "\(id) rendered the same label on both sides of DST")
        }
    }

    // MARK: - offset rendering

    func testOffsetLabelRendersHalfAndQuarterHours() throws {
        // No `at:`, so these go straight to the offset form. None of the four
        // observes DST, so the current-instant offset is stable.
        XCTAssertEqual(TimeZoneAlias.shortLabel(for: try zone("Asia/Kathmandu")), "UTC+5:45")
        XCTAssertEqual(TimeZoneAlias.shortLabel(for: try zone("Asia/Yangon")), "UTC+6:30")
        XCTAssertEqual(TimeZoneAlias.shortLabel(for: try zone("Pacific/Marquesas")), "UTC-9:30")
        XCTAssertEqual(TimeZoneAlias.shortLabel(for: try zone("Asia/Dubai")), "UTC+4")
    }

    // MARK: - resolve / isKnownToken

    func testResolveIsCaseAndWhitespaceInsensitive() throws {
        let paris = try zone("Europe/Paris").identifier
        XCTAssertEqual(TimeZoneAlias.resolve("CET")?.identifier, paris)
        XCTAssertEqual(TimeZoneAlias.resolve("central european")?.identifier, paris)
        XCTAssertEqual(TimeZoneAlias.resolve("Central   European")?.identifier, paris)
        XCTAssertEqual(TimeZoneAlias.resolve("  central\neuropean ")?.identifier, paris)
    }

    /// Every label we render has to resolve back to the zone it came from, or a
    /// second pass over our own output reads the label as an unknown token.
    func testEveryWordLabelWeEmitParsesBack() throws {
        let rendered = ["ET", "CT", "MT", "PT", "HST", "UTC", "UK", "IST", "JST",
                        "KST", "SGT", "HKT", "CN"]
        for label in rendered {
            XCTAssertTrue(
                TimeZoneAlias.isKnownToken(label),
                "we render \(label) but cannot parse it back"
            )
        }
    }

    /// `isKnownToken` is the stricter question — "is this a zone this app
    /// supports?" — and must say no to things `resolve` would happily accept.
    /// An unsupported token that looks supported gets the writer's time
    /// relabelled with the system zone, which is wrong by hours and silent.
    func testIsKnownTokenRejectsWhatResolveWouldAccept() {
        for token in ["NPT", "ACST", "MSK", "WAT", "EST5EDT", "NZ", "Zulu"] {
            XCTAssertFalse(TimeZoneAlias.isKnownToken(token), "\(token) should not be known")
        }
        for token in ["ct", "CT", " Pacific ", "central european"] {
            XCTAssertTrue(TimeZoneAlias.isKnownToken(token), "\(token) should be known")
        }
    }

    /// `TimeZone(abbreviation:)` hands back a *fixed-offset* zone for arbitrary
    /// three-letter strings, which would silently ignore DST. It must not be in
    /// the resolve chain.
    func testResolveDoesNotInventFixedOffsetZones() {
        XCTAssertNil(TimeZoneAlias.resolve("XYZ"))
        XCTAssertNil(TimeZoneAlias.resolve("not a zone"))
    }
}
