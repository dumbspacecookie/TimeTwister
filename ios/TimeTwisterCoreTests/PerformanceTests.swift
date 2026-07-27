//
//  PerformanceTests.swift
//  TimeTwisterCoreTests
//
//  The Swift half of android/core/.../PerformanceTest.kt. Same two quadratic
//  paths, same fix, same shape of guard — see that file for the before/after
//  numbers and why the budgets are loose.
//
//  Worth having on both sides rather than trusting the Kotlin one: the two cores
//  use different regex engines (java.util.regex vs ICU) with different
//  region-scoping APIs, so "bounded on one" does not imply "bounded on the other".
//  The Swift versions of these searches pass an NSRange instead of copying a
//  substring, which is a different mechanism reaching the same result.
//

import XCTest
@testable import TimeTwisterCore

final class PerformanceTests: XCTestCase {

    /// 200 KB of our own output — the worst measured case, because every stamp
    /// triggers the look-behind that used to scan from the start of the string.
    func testParsingLongStampedTextStaysLinear() {
        assertUnder(
            "200KB already-stamped",
            String(repeating: "5pm ET (6pm CT · 3pm PT) ", count: 8_200),
            budget: 5.0
        )
    }

    /// Zone-less times are the other quadratic path: with no zone to consume, every
    /// match ran the unknown-zone lookahead over the rest of the string.
    func testParsingLongZonelessTextStaysLinear() {
        assertUnder(
            "64KB zone-less times",
            String(repeating: "12:30 ", count: 10_666),
            budget: 3.0
        )
    }

    func testParsingOrdinaryLongTextIsCheap() {
        assertUnder(
            "128KB agenda text",
            String(repeating: "standup 9:30 then review 14:00 and retro 16:45 ", count: 2_800),
            budget: 3.0
        )
    }

    private func assertUnder(_ label: String, _ text: String, budget: TimeInterval) {
        // One untimed pass, to match the Kotlin harness and to keep first-call
        // regex compilation out of the measurement.
        _ = TimeConverter.splice(input: text, targets: Self.targets, now: Self.now,
                                 defaultZone: Self.sourceZone)

        let start = Date()
        _ = TimeConverter.splice(input: text, targets: Self.targets, now: Self.now,
                                 defaultZone: Self.sourceZone)
        let elapsed = Date().timeIntervalSince(start)

        XCTAssertLessThan(
            elapsed, budget,
            "\(label) took \(String(format: "%.2f", elapsed))s, over the \(budget)s budget. "
                + "This budget is roughly 25x the expected time, so this almost certainly means "
                + "the parser went quadratic again — look for a substring() or a regex run over "
                + "the whole string inside the per-match loop in TimeParser.detect, or in "
                + "stampRanges."
        )
    }

    static let now: Date = {
        var c = DateComponents()
        c.year = 2026; c.month = 5; c.day = 18; c.hour = 13; c.minute = 0
        c.timeZone = TimeZone(identifier: "UTC")
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: c)!
    }()

    // NOT named `zone`: XCTestCase inherits NSObject, whose legacy `-zone` selector a
    // member called `zone` collides with under Objective-C interop -- "getter for 'zone'
    // ... conflicts with method 'zone()' from superclass 'NSObject'". It is a hard error
    // under Xcode and completely invisible to `swift test` on Windows, which has no
    // Objective-C runtime. That is the whole iOS CI job's reason for existing.
    static let sourceZone = TimeZone(identifier: "America/New_York")!

    static let targets: [TimeZone] = [
        "America/Chicago", "America/New_York", "America/Los_Angeles",
    ].compactMap(TimeZone.init(identifier:))
}
