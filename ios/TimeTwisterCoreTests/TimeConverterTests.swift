//
//  TimeConverterTests.swift
//  TimeTwisterCoreTests
//
//  Created by dumbspacecookie on 4/23/26.
//

import XCTest
@testable import TimeTwisterCore

final class TimeConverterTests: XCTestCase {

    private let ct = TimeZone(identifier: "America/Chicago")!
    private let et = TimeZone(identifier: "America/New_York")!
    private let pt = TimeZone(identifier: "America/Los_Angeles")!

    func testParserFindsSimpleTime() {
        let detected = TimeParser.detect(in: "lets do 5pm CT")
        XCTAssertEqual(detected.count, 1)
        XCTAssertEqual(detected.first?.hour, 17)
        XCTAssertEqual(detected.first?.minute, 0)
        XCTAssertEqual(detected.first?.timeZone.identifier, ct.identifier)
    }

    func testParserHandlesMinutes() {
        let detected = TimeParser.detect(in: "how about 5:30pm pacific")
        XCTAssertEqual(detected.first?.hour, 17)
        XCTAssertEqual(detected.first?.minute, 30)
        XCTAssertEqual(detected.first?.timeZone.identifier, pt.identifier)
    }

    func testParserIgnoresBareNumbers() {
        let detected = TimeParser.detect(in: "room 5 is open")
        XCTAssertTrue(detected.isEmpty, "bare '5' without am/pm or TZ should not match")
    }

    func testParser24Hour() {
        let detected = TimeParser.detect(in: "landing at 17:00 ET")
        XCTAssertEqual(detected.first?.hour, 17)
        XCTAssertEqual(detected.first?.minute, 0)
        XCTAssertEqual(detected.first?.timeZone.identifier, et.identifier)
    }

    func testParserNoon() {
        let detected = TimeParser.detect(in: "call at noon CT")
        XCTAssertEqual(detected.count, 1)
        XCTAssertEqual(detected.first?.hour, 12)
        XCTAssertEqual(detected.first?.minute, 0)
        XCTAssertEqual(detected.first?.timeZone.identifier, ct.identifier)
    }

    func testParserMidnight() {
        let detected = TimeParser.detect(in: "deploy at midnight ET")
        XCTAssertEqual(detected.count, 1)
        XCTAssertEqual(detected.first?.hour, 0)
        XCTAssertEqual(detected.first?.minute, 0)
        XCTAssertEqual(detected.first?.timeZone.identifier, et.identifier)
    }

    func testParserNoonWithoutTzStillMatches() {
        // noon/midnight self-disambiguate — no TZ required.
        let detected = TimeParser.detect(in: "see you at noon")
        XCTAssertEqual(detected.count, 1)
        XCTAssertEqual(detected.first?.hour, 12)
        XCTAssertEqual(detected.first?.hadExplicitZone, false)
    }

    func testConverterStampAcrossZones() {
        // 5pm CT on a fixed date should render 6pm ET and 3pm PT.
        let fixed = Date(timeIntervalSince1970: 1_700_000_000) // arbitrary stable anchor
        let detected = DetectedTime(
            hour: 17, minute: 0,
            timeZone: ct,
            hadExplicitZone: true,
            range: NSRange(location: 0, length: 6),
            originalText: "5pm CT"
        )
        let stamp = TimeConverter.renderStamp(
            for: detected,
            targets: [ct, et, pt],
            now: fixed
        )
        XCTAssertTrue(stamp.contains("5pm CT"), stamp)
        XCTAssertTrue(stamp.contains("6pm ET"), stamp)
        XCTAssertTrue(stamp.contains("3pm PT"), stamp)
    }

    func testConverterOmitsSourceFromTargets() {
        let fixed = Date(timeIntervalSince1970: 1_700_000_000)
        let detected = DetectedTime(
            hour: 17, minute: 0,
            timeZone: ct,
            hadExplicitZone: true,
            range: NSRange(),
            originalText: "5pm CT"
        )
        let stamp = TimeConverter.renderStamp(for: detected, targets: [ct], now: fixed)
        XCTAssertFalse(stamp.contains("("), "no parens when targets == [source]")
    }

    // MARK: - coverage hardening (regressions caught by StampDemo)

    func testParserLowercaseTzToken() {
        // regex is case-insensitive; "utc" lowercase should resolve to UTC.
        //
        // Asserted by offset and rendered label, not by identifier string:
        // Foundation treats "UTC" and "GMT" as aliases and canonicalises one to
        // the other, and which name survives differs between Apple's Foundation
        // and swift-corelibs. Both of those are UTC as far as this app is
        // concerned, and the label is what the user actually sees.
        let detected = TimeParser.detect(in: "ship by 11pm utc")
        XCTAssertEqual(detected.first?.hour, 23)
        let zone = try? XCTUnwrap(detected.first?.timeZone)
        XCTAssertEqual(zone?.secondsFromGMT(for: Date()), 0)
        XCTAssertEqual(zone.map { TimeZoneAlias.shortLabel(for: $0) }, "UTC")
    }

    func testParserUppercaseAmPm() {
        let detected = TimeParser.detect(in: "call at 5PM CT")
        XCTAssertEqual(detected.first?.hour, 17)
        XCTAssertEqual(detected.first?.timeZone.identifier, ct.identifier)
    }

    func testParserDottedAmPm() {
        // "p.m." form, dots get stripped before comparison.
        let detected = TimeParser.detect(in: "call at 5p.m. CT")
        XCTAssertEqual(detected.first?.hour, 17)
    }

    func testParserMultiWordZoneEastern() {
        let detected = TimeParser.detect(in: "demo at 2:15pm eastern")
        XCTAssertEqual(detected.first?.hour, 14)
        XCTAssertEqual(detected.first?.minute, 15)
        XCTAssertEqual(detected.first?.timeZone.identifier, et.identifier)
    }

    func testParser12pmIsNoonNot12am() {
        // historic gotcha: 12pm in 12-hour clock is 12:00, NOT 0:00.
        let detected = TimeParser.detect(in: "call at 12pm CT")
        XCTAssertEqual(detected.first?.hour, 12)
    }

    func testParser12amIsMidnight() {
        // and 12am is 0:00, not 12:00.
        let detected = TimeParser.detect(in: "deploy at 12am ET")
        XCTAssertEqual(detected.first?.hour, 0)
    }

    func testConverterHalfHourOffsetZone() {
        // India is UTC+5:30 — the :30 must propagate through to rendered minutes.
        let ist = TimeZone(identifier: "Asia/Kolkata")!
        let fixed = Date(timeIntervalSince1970: 1_714_550_400) // 2026-05-01 around midday UTC
        let detected = DetectedTime(
            hour: 19, minute: 0,
            timeZone: ist,
            hadExplicitZone: true,
            range: NSRange(),
            originalText: "7pm IST"
        )
        let stamp = TimeConverter.renderStamp(for: detected, targets: [ist, et], now: fixed)
        XCTAssertTrue(stamp.contains(":30"), "expected ':30' in cross-zone render, got: \(stamp)")
    }
}
