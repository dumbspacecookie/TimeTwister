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
}
