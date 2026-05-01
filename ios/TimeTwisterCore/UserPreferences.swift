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
        // User's own zone + the other three US zones, deduped.
        var ids = [TimeZone.current.identifier]
        for id in ["America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles"] {
            if !ids.contains(id) { ids.append(id) }
        }
        return ids
    }
}
