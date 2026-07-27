//
//  UserPreferencesTests.swift
//  TimeTwisterCoreTests
//
//  The Swift half of android/app/.../UserPreferencesCodecTest.kt.
//
//  UserPreferences had no tests of any kind until 2026-07-27, on either the persistence
//  or the defaults, despite being the only shared state between the container app and the
//  keyboard extension. It is testable without a device because of the `init(defaults:)`
//  seam and because `computeDefaults` takes the system zone as an argument rather than
//  reading `TimeZone.current` — which matters here more than on Android, since
//  swift-corelibs silently ignores attempts to set `NSTimeZone.default`, so there is no
//  way to fake the process zone off a Mac.
//
//  The defaults cases are deliberately the same ones the Kotlin test asserts. The two
//  cores are supposed to produce an identical default zone list, and until 2026-07-27 they
//  did not: Android capped the list and iOS did not.
//

import XCTest
// Plain `import`, not `@testable`: everything under test here is public API, and every
// other file in this target does the same. @testable additionally requires the framework
// to have been built with testability enabled, which is an Xcode build setting nothing in
// this repo pins — a failure `swift test` on Windows could not have shown me.
import TimeTwisterCore

final class UserPreferencesTests: XCTestCase {

    /// A UserDefaults instance nothing else touches, so tests cannot leak into each other
    /// or into a real App Group.
    private func isolatedDefaults(_ name: String = #function) -> UserDefaults {
        let suite = "com.timetwister.tests.\(name)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    private func ids(_ zones: [TimeZone]) -> [String] { zones.map(\.identifier) }

    // MARK: - persistence

    func testWritingThenReadingPreservesOrder() {
        let prefs = UserPreferences(defaults: isolatedDefaults())
        let zones = ["America/New_York", "Asia/Kolkata", "Europe/London"]
            .compactMap { TimeZone(identifier: $0) }
        prefs.targetZones = zones
        // Order is not incidental: it is the order the stamp renders in.
        XCTAssertEqual(ids(prefs.targetZones), ids(zones))
    }

    func testUnsetMeansDefaults() {
        let prefs = UserPreferences(defaults: isolatedDefaults())
        XCTAssertEqual(
            ids(prefs.targetZones),
            ids(UserPreferences.computeDefaults(systemZone: .current)),
        )
    }

    func testAnEmptyListIsHonouredRatherThanTreatedAsUnset() {
        // The Kotlin side had a bug here worth not repeating: collapsing "empty" into
        // "never configured" resurrected all the defaults, so deleting your last zone read
        // as the app ignoring you.
        let prefs = UserPreferences(defaults: isolatedDefaults())
        prefs.targetZones = []
        XCTAssertEqual(prefs.targetZones, [])
    }

    func testAnUnknownIdentifierIsDroppedRatherThanCrashing() {
        // A tzdb update can retire an identifier sitting in someone's saved list. Losing
        // one zone is recoverable; failing every read is not.
        let defaults = isolatedDefaults()
        defaults.set(["America/New_York", "Mars/Olympus_Mons"], forKey: "targetZones")
        let prefs = UserPreferences(defaults: defaults)
        XCTAssertEqual(ids(prefs.targetZones), ["America/New_York"])
    }

    func testAnEntirelyUnparseableStoredListReadsAsEmpty() {
        // Distinct from unset on purpose: this *is* a configuration, just a broken one,
        // and silently substituting defaults would hide the breakage.
        let defaults = isolatedDefaults()
        defaults.set(["!!!", "???"], forKey: "targetZones")
        XCTAssertEqual(UserPreferences(defaults: defaults).targetZones, [])
    }

    // MARK: - defaults, at zones other than this process's

    func testDefaultsNeverExceedTheRecommendedMaximum() {
        // The regression this seam exists for: "your zone + four US zones" is five entries
        // for most of the world, over the cap, so a fresh install started life in a
        // warning state on Android — and rendered a wider stamp than Android on iOS.
        let worthChecking = [
            "Europe/London", "Europe/Berlin", "Asia/Kolkata", "Asia/Tokyo",
            "Australia/Sydney", "America/Sao_Paulo", "Africa/Lagos", "UTC",
            // Not outside the US, and the reason this was easy to miss from a US desk:
            "America/Phoenix", "America/Anchorage", "Pacific/Honolulu",
            // ...and the four that dedupe:
            "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles",
        ]
        for id in worthChecking {
            let zone = TimeZone(identifier: id)!
            let defaults = UserPreferences.computeDefaults(systemZone: zone)
            XCTAssertLessThanOrEqual(
                defaults.count, UserPreferences.maxRecommendedZones,
                "defaults at \(id) were \(defaults.count): \(ids(defaults))",
            )
        }
    }

    func testDefaultsLeadWithTheUsersOwnZone() {
        for id in ["Europe/London", "America/Phoenix", "Asia/Tokyo", "America/Chicago"] {
            let defaults = UserPreferences.computeDefaults(systemZone: TimeZone(identifier: id)!)
            XCTAssertEqual(defaults.first?.identifier, id)
        }
    }

    func testANonUSUserKeepsEasternCentralPacificAndLosesMountain() {
        // Which one gets dropped is a judgement call, so pin it — and pin it identically
        // to the Kotlin test, because the whole point is that both cores agree.
        let defaults = UserPreferences.computeDefaults(
            systemZone: TimeZone(identifier: "Europe/London")!)
        XCTAssertEqual(
            ids(defaults),
            ["Europe/London", "America/New_York", "America/Chicago", "America/Los_Angeles"],
        )
    }

    func testAMountainUserKeepsMountainBecauseItIsTheirOwnZone() {
        let defaults = UserPreferences.computeDefaults(
            systemZone: TimeZone(identifier: "America/Denver")!)
        XCTAssertTrue(ids(defaults).contains("America/Denver"), "\(ids(defaults))")
        XCTAssertEqual(defaults.count, 4)
    }

    func testAUSDefaultListDedupesTheUsersOwnZone() {
        let defaults = UserPreferences.computeDefaults(
            systemZone: TimeZone(identifier: "America/New_York")!)
        XCTAssertEqual(
            ids(defaults),
            [
                "America/New_York", "America/Chicago",
                "America/Denver", "America/Los_Angeles",
            ],
        )
    }

    func testDefaultsContainNoDuplicatesAtAnyAmericanZone() {
        for id in TimeZone.knownTimeZoneIdentifiers.sorted()
        where id.hasPrefix("America/") {
            guard let zone = TimeZone(identifier: id) else { continue }
            let list = ids(UserPreferences.computeDefaults(systemZone: zone))
            XCTAssertEqual(
                Set(list).count, list.count,
                "duplicate in defaults at \(id): \(list)",
            )
        }
    }
}
