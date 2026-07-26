//
//  RedTeamRegressionTests.swift
//  TimeTwisterCoreTests
//
//  The Swift half of android/core/.../RedTeamRegressionTest.kt — same defects,
//  same inputs, same expected strings. One test per defect found in the
//  2026-07-25 red-team pass, each pinned to the exact input that produced the bad
//  output. Every case here was confirmed by running the old code, not by reading
//  it — the strings in the comments are what the tool actually used to put into a
//  user's message.
//
//  Until this file existed the iOS core still shipped every one of them.
//
//  The clock and the system zone are pinned; nothing here may depend on wall time.
//

import XCTest
@testable import TimeTwisterCore

final class RedTeamRegressionTests: XCTestCase {

    private let ct = TimeZone(identifier: "America/Chicago")!
    private let et = TimeZone(identifier: "America/New_York")!
    private let pt = TimeZone(identifier: "America/Los_Angeles")!
    private var targets: [TimeZone] { [ct, et, pt] }

    /// Same fixed clock the Kotlin suite and the eval corpus use:
    /// 2026-05-18T09:00:00-04:00 == 2026-05-18T13:00:00Z.
    private static let now = date(utc: (2026, 5, 18, 13, 0))

    private var now: Date { Self.now }

    private static func date(utc c: (Int, Int, Int, Int, Int)) -> Date {
        var comps = DateComponents()
        comps.year = c.0; comps.month = c.1; comps.day = c.2
        comps.hour = c.3; comps.minute = c.4
        comps.timeZone = TimeZone(identifier: "UTC")
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: comps)!
    }

    /// Pinned so the "writer named no zone" fallback is deterministic.
    ///
    /// Passed as an argument rather than installed with `NSTimeZone.default`:
    /// that setter is honoured by Apple's Foundation and silently ignored by
    /// swift-corelibs, so the Kotlin suite's trick of pinning the process default
    /// looks like it works here and does not.
    private var pinnedZone: TimeZone { et }

    private func splice(_ input: String, at when: Date? = nil) -> String {
        TimeConverter.splice(
            input: input, targets: targets, now: when ?? now, defaultZone: pinnedZone
        )
    }

    private func maybeSplice(
        _ input: String, readOnly: Bool = false, at when: Date? = nil
    ) -> String? {
        TimeConverter.maybeSplice(
            input: input, targets: targets, readOnly: readOnly,
            now: when ?? now, defaultZone: pinnedZone
        )
    }

    private func detectLast(_ input: String) -> DetectedTime? {
        TimeParser.detectLast(in: input, defaultZone: pinnedZone)
    }

    // MARK: - RT-01
    // Was: "lets do 5pm CT (6pm ET · 3pm PT)" run twice produced
    //      "lets do 5pm CT (6pm ET · 3pm PT (5pm CT · 6pm ET))"

    func testSecondPassDoesNotNestTheStampInsideItself() {
        let once = splice("lets do 5pm CT")
        XCTAssertEqual(once, "lets do 5pm CT (6pm ET · 3pm PT)")
        XCTAssertEqual(splice(once), once, "a second pass must be a no-op")
    }

    func testRepeatedPassesConverge() {
        var text = splice("meet at 7pm IST tomorrow")
        let first = text
        for _ in 0..<5 { text = splice(text) }
        XCTAssertEqual(text, first)
    }

    /// The same guarantee stated the way a host actually consumes it.
    func testMaybeSpliceReportsNoChangeOnAlreadyStampedText() {
        let once = splice("lets do 5pm CT")
        XCTAssertNil(maybeSplice(once))
    }

    // MARK: - RT-02
    // Was: "use 12 pt font" -> "use 12pm PT (2pm CT · 3pm ET) font", and friends.
    // A bare number next to a zone token is not a time.

    func testBareNumberPlusZoneTokenIsNotATime() {
        for input in [
            "use 12 pt font",
            "make the header 18 pt",
            "I'm at 5 London Road",
            "top 5 est. results",
            "1 ct diamond",
            "12 Central Ave",
            "won 3-1 pt",
            "score was 12-3 mt",
            "half past 5 ET",
            "Section 5 pt 2",
            "converted 5 ct to usd",
            "he dropped 40 pt",
        ] {
            XCTAssertEqual(splice(input), input, "must not rewrite: \(input)")
        }
    }

    // MARK: - RT-04 / RT-08
    // Was: a comma, bracket or non-breaking space between the time and the zone
    // broke the match, and the time was silently re-attributed to the SYSTEM zone
    // and rendered with full confidence — wrong by hours, with no signal.

    func testSeparatorsBetweenTimeAndZoneDoNotLoseTheZone() {
        let inputs = [
            "5pm CT",
            "5pm, CT",
            "5pm (CT)",
            "5pm [CT]",
            "5pm\u{00A0}CT",   // non-breaking space — everything pasted from Word/Docs
            "5pm\u{202F}CT",   // narrow no-break space — what Apple/CLDR emit
            "5pm\nCT",         // zone wrapped onto the next line
            "5pm\tCT",
            "5pm  CT",
        ]
        for input in inputs {
            guard let detected = detectLast(input) else {
                return XCTFail("no detection for: \(input.debugDescription)")
            }
            XCTAssertTrue(detected.hadExplicitZone,
                          "zone must be explicit for: \(input.debugDescription)")
            XCTAssertEqual(detected.timeZone.identifier, ct.identifier,
                           "wrong zone for: \(input.debugDescription)")
        }
        XCTAssertEqual(splice("5pm CT"), "5pm CT (6pm ET · 3pm PT)")
    }

    // MARK: - RT-03 / RT-07
    // Was: "5pm Eastern Time" -> "5pm ET (…) Time"  (dangling word)
    //      "5pm EST/EDT"      -> "5pm ET (…)/EDT"   (dangling fragment)
    //      "3pm Central European Time" resolved to US Central, not Paris.

    func testSpelledOutAndPairedZonesLeaveNothingDangling() {
        XCTAssertEqual(splice("call me at 5pm Eastern Time"),
                       "call me at 5pm ET (4pm CT · 2pm PT)")
        XCTAssertEqual(splice("lets do 5pm EST/EDT"),
                       "lets do 5pm ET (4pm CT · 2pm PT)")
        XCTAssertEqual(detectLast("3pm Central European Time")?.timeZone.identifier,
                       TimeZone(identifier: "Europe/Paris")?.identifier,
                       "central european must not resolve to US Central")
    }

    // MARK: - RT-05
    // Was: "5pm America/Toronto (…)" — a raw IANA id rendered into a chat message.

    func testUnlistedZonesNeverRenderARawIanaId() throws {
        for id in ["America/Toronto", "Europe/Berlin", "Europe/Madrid", "Asia/Dubai"] {
            let zone = try XCTUnwrap(TimeZone(identifier: id))
            let label = TimeZoneAlias.shortLabel(for: zone, at: now)
            XCTAssertFalse(label.contains("/"),
                           "label for \(id) must not be an IANA id, got '\(label)'")
        }
    }

    // MARK: - RT-05 (secondary)
    // Was: targets on the same offset as the source were listed anyway, e.g.
    // "5pm America/Toronto (5pm ET · …)" — width with no information.

    func testTargetsSharingTheSourceOffsetAreNotRepeated() throws {
        let toronto = try XCTUnwrap(TimeZone(identifier: "America/Toronto"))
        let detected = DetectedTime(
            hour: 17, minute: 0, timeZone: toronto, hadExplicitZone: true,
            range: NSRange(location: 0, length: 3), originalText: "5pm"
        )
        let stamp = TimeConverter.renderStamp(
            for: detected, targets: [toronto, et, ct, pt], now: now
        )
        XCTAssertFalse(stamp.contains("5pm ET"), "must not repeat the source offset: \(stamp)")
    }

    // MARK: - RT-06
    // Was: "meet at 11pm PT (1am CT · 2am ET)" with no day marker — an ET reader
    // sees "2am" and books tonight.

    func testConversionsOnAnotherCalendarDayAreMarked() {
        XCTAssertEqual(splice("meet at 11pm PT"),
                       "meet at 11pm PT (1am CT +1d · 2am ET +1d)")
        XCTAssertEqual(splice("deploy at 12am ET"),
                       "deploy at 12am ET (11pm CT -1d · 9pm PT -1d)")
    }

    // MARK: - RT-11
    // Was: "deploy at 2:30am ET" on a spring-forward day -> "deploy at 3:30am ET (…)",
    // silently replacing a time the user actually typed with one they did not.

    func testATimeInsideTheDstGapIsLeftAlone() {
        // 2026-03-08 00:30 ET, i.e. 05:30Z — an hour before ET springs forward,
        // so "2:30am" resolves onto that same day and lands in the hole.
        let springForward = Self.date(utc: (2026, 3, 8, 5, 30))
        let input = "deploy at 2:30am ET"
        XCTAssertEqual(splice(input, at: springForward), input)
        XCTAssertNil(maybeSplice(input, at: springForward))
    }

    // MARK: - RT-12
    // Was: Australia/Sydney rendered "AEST" in January (it is on AEDT) and Paris
    // rendered "CET" all summer. The number was right, the label contradicted it.

    /// Asserted exactly, on every platform, because these spellings are ours.
    ///
    /// They were briefly written as "the abbreviation OR our offset form", when
    /// the label still came from `TimeZone.abbreviation(for:)` — Apple's
    /// Foundation answers "AEDT", swift-corelibs on Windows has no entry and
    /// answers an offset-shaped stand-in. `TimeZoneAlias` now carries the
    /// standard/daylight pair for every DST-observing zone the app supports and
    /// picks between them with `isDaylightSavingTime(for:)`, so the output is
    /// identical everywhere and the assertion can be exact again.
    func testDstSpecificLabelsFollowTheInstant() throws {
        let january = Self.date(utc: (2026, 1, 15, 13, 0))
        let july = Self.date(utc: (2026, 7, 15, 13, 0))

        let cases: [(id: String, winter: String, summer: String)] = [
            ("Australia/Sydney", "AEDT", "AEST"),   // southern hemisphere: inverted
            ("Pacific/Auckland", "NZDT", "NZST"),
            ("Europe/Paris", "CET", "CEST"),
            ("Europe/Helsinki", "EET", "EEST"),
            ("America/Anchorage", "AKST", "AKDT"),
        ]

        for c in cases {
            let tz = try XCTUnwrap(TimeZone(identifier: c.id))
            XCTAssertEqual(TimeZoneAlias.shortLabel(for: tz, at: january), c.winter, c.id)
            XCTAssertEqual(TimeZoneAlias.shortLabel(for: tz, at: july), c.summer, c.id)
        }
    }

    // MARK: - G014-G017
    // Was: "call 7pm NPT" -> "call 7pm ET (…) NPT". The writer named a zone we do
    // not support; guessing the system zone relabels their time as something else.

    func testAnUnsupportedZoneTokenSuppressesTheGuess() {
        for input in ["call 7pm NPT", "call 7pm ACST", "call 7pm MSK", "call 7pm WAT"] {
            XCTAssertEqual(splice(input), input, "must not guess a zone for: \(input)")
        }
    }

    // MARK: - G021
    // Was: "12 noon ET" -> "12 12pm ET (…)" — the leading 12 stranded.

    func testTwelveNoonAbsorbsTheLeadingTwelve() {
        XCTAssertEqual(splice("12 noon ET"), "12pm ET (11am CT · 9am PT)")
    }

    // MARK: - Unicode
    // Was: "5pm CTです" lost the zone, because \b is Unicode-aware and there is no
    // boundary between "T" and "で".

    func testNonLatinTextTouchingTheZoneTokenKeepsTheZone() {
        XCTAssertEqual(detectLast("5pm CTです")?.timeZone.identifier, ct.identifier)
        XCTAssertEqual(splice("5pm CTです"), "5pm CT (6pm ET · 3pm PT)です")
    }

    /// A token running into more ASCII letters is still not a zone.
    func testZoneTokensDoNotMatchInsideLongerWords() {
        XCTAssertEqual(splice("meeting at 5pmm"), "meeting at 5pmm")
        XCTAssertEqual(splice("5pm CTX build"), "5pm CTX build")
    }

    // MARK: - read-only guard
    // The iOS Action Extension can be handed a selection it may not rewrite.

    func testReadOnlySelectionsAreNeverRewritten() {
        XCTAssertNil(maybeSplice("lets do 5pm CT", readOnly: true))
        XCTAssertEqual(maybeSplice("lets do 5pm CT"), "lets do 5pm CT (6pm ET · 3pm PT)")
    }
}
