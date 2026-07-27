//
//  UserPreferences.swift
//  TimeTwisterCore
//
//  Created by dumbspacecookie on 4/21/26.
//

import Foundation

/// Shared configuration between the container app (where the user picks
/// their timezones) and the keyboard extension (which reads them).
/// Backed by App Group UserDefaults so both processes see the same data.
public final class UserPreferences {

    public static let appGroup = "group.com.timetwister"
    public static let shared = UserPreferences()

    private let defaults: UserDefaults

    private enum Key {
        static let targetZones = "targetZones" // [String] of IANA identifiers
    }

    public init(defaults: UserDefaults? = nil) {
        self.defaults = defaults ?? UserDefaults(suiteName: Self.appGroup) ?? .standard
    }

    /// The set of timezones we should render conversions into.
    /// Defaults to a sensible US-centric starter set on first launch.
    public var targetZones: [TimeZone] {
        get {
            let ids = defaults.stringArray(forKey: Key.targetZones) ?? defaultIdentifiers
            return ids.compactMap { TimeZone(identifier: $0) }
        }
        set {
            defaults.set(newValue.map(\.identifier), forKey: Key.targetZones)
        }
    }

    private var defaultIdentifiers: [String] {
        Self.computeDefaults(systemZone: .current).map(\.identifier)
    }

    /// Soft ceiling on target zones, matching the Kotlin core's `MAX_RECOMMENDED_ZONES`.
    /// Beyond this the stamp stops being readable inline.
    public static let maxRecommendedZones = 4

    /// Geographic east→west, which is the order the stamp reads best in.
    private static let usZones = [
        "America/New_York",
        "America/Chicago",
        "America/Denver",
        "America/Los_Angeles",
    ]

    /// Which defaults may be given up, first to go at the front, when the user's own zone
    /// is not already one of the four. Mountain goes first: least populous US zone, and
    /// anyone who wants it — Denver, Phoenix — reads it off their own zone anyway.
    private static let droppableDefaults = ["America/Denver", "America/Chicago"]

    /// Split out from `defaultIdentifiers` for two reasons.
    ///
    /// One, it can be tested at a zone other than the one the test process happens to run
    /// in; `TimeZone.current` cannot be usefully overridden under swift-corelibs (setting
    /// `NSTimeZone.default` is silently ignored there), so a seam is the only way to check
    /// this at all off a Mac.
    ///
    /// Two, the trim is a **fix, and a cross-core parity fix at that**. "Your zone plus the
    /// four US zones" is *five* entries for everyone outside the US — and for Phoenix,
    /// Anchorage and Honolulu, the part that made it easy to miss from a US desk. Kotlin
    /// started capping this on 2026-07-26; until 2026-07-27 iOS did not, so the same
    /// untouched default state rendered a four-zone stamp on Android and a five-zone stamp
    /// on iOS. This is a straight port of `UserPreferences.computeDefaults` on the Kotlin
    /// side, droppable order included, and `UserPreferencesTests` pins the shared cases.
    public static func computeDefaults(systemZone: TimeZone) -> [TimeZone] {
        // User's own zone first, then the US zones, deduped, order-preserving.
        var ids = [systemZone.identifier]
        for id in usZones where !ids.contains(id) { ids.append(id) }

        for droppable in droppableDefaults {
            if ids.count <= maxRecommendedZones { break }
            // Never drop the zone the user is actually in.
            guard droppable != systemZone.identifier else { continue }
            ids.removeAll { $0 == droppable }
        }
        return ids.compactMap { TimeZone(identifier: $0) }
    }
}
