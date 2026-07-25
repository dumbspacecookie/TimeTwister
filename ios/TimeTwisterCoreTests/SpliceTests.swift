//
//  SpliceTests.swift
//  TimeTwisterCoreTests
//
//  Mirrors android/core/.../SpliceTest.kt. Same anchor, same expectations.
//  The Swift side doesn't need maybeSplice — the iOS Action Extension calls
//  splice directly and the host decides whether to accept the returned text.
//

import XCTest
@testable import TimeTwisterCore

final class SpliceTests: XCTestCase {

    private let targets: [TimeZone] = [
        TimeZone(identifier: "America/Chicago")!,
        TimeZone(identifier: "America/New_York")!,
        TimeZone(identifier: "America/Los_Angeles")!,
    ]

    // Anchor: 2026-01-15 08:00 EST — solidly inside EST, no DST drift.
    private let anchor: Date = {
        var c = DateComponents()
        c.year = 2026; c.month = 1; c.day = 15; c.hour = 8; c.minute = 0
        c.timeZone = TimeZone(identifier: "America/New_York")
        return Calendar(identifier: .gregorian).date(from: c)!
    }()

    func testSpliceReplacesDetectedRangeInPlace() {
        let out = TimeConverter.splice(input: "lets do 5pm CT", targets: targets, now: anchor)
        XCTAssertEqual(out, "lets do 5pm CT (6pm ET · 3pm PT)")
    }

    func testSplicePreservesTrailingText() {
        // Anchor is EST (Jan), not EDT, so offsets are 1hr earlier than the
        // README example. 7pm IST = 13:30 UTC = 8:30 EST / 7:30 CST / 5:30 PST.
        let out = TimeConverter.splice(input: "meet at 7pm IST tomorrow", targets: targets, now: anchor)
        XCTAssertEqual(out, "meet at 7pm IST (7:30am CT · 8:30am ET · 5:30am PT) tomorrow")
    }

    func testSpliceReturnsInputUnchangedWhenNoDetection() {
        let input = "room 5 is open"
        XCTAssertEqual(TimeConverter.splice(input: input, targets: targets, now: anchor), input)
    }

    func testSpliceReturnsInputUnchangedWhenEmpty() {
        XCTAssertEqual(TimeConverter.splice(input: "", targets: targets, now: anchor), "")
    }

    func testSpliceOnLastTimeWhenMultiplePresent() {
        // detectLast wins so the user sees the most recent reference stamped.
        let out = TimeConverter.splice(input: "9am CT standup, demo at 2pm PT", targets: targets, now: anchor)
        XCTAssertEqual(out, "9am CT standup, demo at 2pm PT (4pm CT · 5pm ET)")
    }
}
