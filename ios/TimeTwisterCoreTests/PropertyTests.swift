//
//  PropertyTests.swift
//  TimeTwisterCoreTests
//
//  The Swift half of android/core/.../PropertyTest.kt.
//
//  Where EvalCorpusTests grades fixed examples, this file states things that must
//  hold for *every* input and then hunts for counterexamples. No property-testing
//  library: a seeded generator and plain assertions, so a red run reproduces
//  exactly.
//
//  Three of the Kotlin core's nine invariants are ported here, chosen because they
//  are the ones that catch the defects a differential run against Kotlin actually
//  found:
//
//    preserves-context  Swift's splice used NSString.replacingCharacters, which on
//                       swift-corelibs rounds a UTF-16 range end down to a grapheme
//                       boundary. "5pm CT" + a combining acute spliced with the "T"
//                       duplicated. ~100 inputs affected. This property is the
//                       direct detector: the text around the match must survive
//                       untouched.
//    idempotent         splice(splice(x)) == splice(x). Catches the same corruption
//                       second-hand, and catches a label we emit but cannot read
//                       back — which was true of every "UTC+2" style offset label
//                       and therefore of ~400 of the platform's zones.
//    labels-reparse     every label we render resolves back to the zone it came
//                       from. The invariant the whole TimeZoneAlias file is built
//                       around, and the one that was silently false.
//
//  The remaining six (source-label-first, distinct-targets, round-trip,
//  whitespace-stable, no-detect-identity, never-throws) are still Kotlin-only.
//
//  The generator is a deliberate clone of the Kotlin one, down to reimplementing
//  java.util.Random's LCG, so that both cores explore the *same* 500 inputs in the
//  same order for the same seed. That is worth the ~20 lines: it turns "both cores
//  pass their property tests" into "both cores pass on identical inputs", which is
//  the stronger claim and the one that matters for a shared implementation.
//

import XCTest
@testable import TimeTwisterCore

// MARK: - java.util.Random, reimplemented

/// The exact LCG from `java.util.Random`, so a seed produces the same sequence
/// here as it does in the Kotlin suite. Not a general-purpose RNG — its only job
/// is to agree with the other core.
struct JavaRandom {
    private var seed: UInt64
    private static let multiplier: UInt64 = 0x5DEECE66D
    private static let mask: UInt64 = (1 << 48) - 1

    init(seed: Int64) {
        self.seed = (UInt64(bitPattern: seed) ^ Self.multiplier) & Self.mask
    }

    private mutating func next(_ bits: Int) -> Int32 {
        seed = (seed &* Self.multiplier &+ 0xB) & Self.mask
        return Int32(truncatingIfNeeded: seed >> UInt64(48 - bits))
    }

    mutating func nextInt(_ bound: Int) -> Int {
        precondition(bound > 0)
        let b = Int32(bound)
        // Power of two: Java takes the high bits rather than the remainder.
        if (b & -b) == b {
            return Int((Int64(b) &* Int64(next(31))) >> 31)
        }
        // Rejection loop, including Java's Int32-overflow test for the biased tail.
        while true {
            let bits = next(31)
            let val = bits % b
            if bits &- val &+ (b &- 1) >= 0 { return Int(val) }
        }
    }

    mutating func nextBoolean() -> Bool { next(1) != 0 }
}

final class PropertyTests: XCTestCase {

    // MARK: - the properties

    /// Everything outside the detected range must survive the splice byte for byte.
    func testSpliceNeverLosesTheSurroundingText() {
        runProperty("preserves-context") { input, targets in
            guard let detected = TimeParser.detectLast(in: input, defaultZone: Self.zone) else {
                return nil
            }
            let ns = input as NSString
            let prefix = ns.substring(to: detected.range.location)
            let suffix = ns.substring(from: detected.range.location + detected.range.length)
            let out = TimeConverter.splice(
                input: input, targets: targets, now: Self.now, defaultZone: Self.zone
            )
            // Compared in UTF-16, deliberately. Comparing Characters is what hid the
            // grapheme-rounding bug in the first place: a trailing "T" fused into
            // the following combining mark reads as one Character either way.
            let outUnits = Array(out.utf16)
            let prefixUnits = Array(prefix.utf16)
            let suffixUnits = Array(suffix.utf16)

            if !outUnits.starts(with: prefixUnits) {
                return "prefix lost: expected output to start with \(show(prefix)), got \(show(out))"
            }
            if !outUnits.suffix(suffixUnits.count).elementsEqual(suffixUnits) {
                return "suffix lost: expected output to end with \(show(suffix)), got \(show(out))"
            }
            if outUnits.count < prefixUnits.count + suffixUnits.count {
                return "output is shorter than the untouched context: \(show(out))"
            }

            // The three checks above are the Kotlin property, and on their own they
            // are too weak: they say the context survives at both ends, but not that
            // *nothing else* got in. The grapheme-rounding bug this property was
            // ported to catch duplicates a character in the middle, leaving both
            // ends intact — so all three passed while the message was corrupted.
            //
            // Reconstructing the whole expected string closes that. Only meaningful
            // when splice actually rewrote something and no trailing stamp was
            // absorbed; both of those take different paths that this cannot predict.
            let end = detected.range.location + detected.range.length
            guard TimeParser.trailingStampRange(in: input, from: end) == nil,
                  !TimeConverter.isUnrepresentable(detected, now: Self.now)
            else { return nil }

            let stamp = TimeConverter.renderStamp(
                for: detected, targets: targets, now: Self.now
            )
            let expected = prefixUnits + Array(stamp.utf16) + suffixUnits
            if outUnits != expected {
                return "output is not exactly prefix + stamp + suffix:\n"
                    + "        expected \(show(String(utf16CodeUnits: expected, count: expected.count)))\n"
                    + "        actual   \(show(out))"
            }
            return nil
        }
    }

    /// A second pass over our own output must change nothing.
    func testSpliceIsIdempotent() {
        runProperty("idempotent") { input, targets in
            let once = TimeConverter.splice(
                input: input, targets: targets, now: Self.now, defaultZone: Self.zone
            )
            let twice = TimeConverter.splice(
                input: once, targets: targets, now: Self.now, defaultZone: Self.zone
            )
            guard once != twice else { return nil }
            return "second pass changed the text again:\n"
                + "        once  \(show(once))\n"
                + "        twice \(show(twice))"
        }
    }

    /// Every label we render must resolve back to the zone it came from.
    ///
    /// Deterministic, not generated: walks every zone the alias table can produce.
    func testEveryRenderedZoneLabelCanBeReadBackByOurOwnParser() {
        var violations: [String] = []
        for id in Set(TimeZoneAlias.map.values).sorted() {
            guard let zone = TimeZone(identifier: id) else { continue }
            // Labelled the way the renderer does it — against the instant being
            // shown. Zones that observe DST have no single correct abbreviation, so
            // asking without an instant yields a bare offset; that is a property of
            // the question, not a defect.
            let label = TimeZoneAlias.shortLabel(for: zone, at: Self.now)
            let back = TimeZoneAlias.resolve(label)
            // Compared by offset at this instant rather than by identifier: an
            // offset label resolves to a fixed-offset zone whose id is never the
            // IANA one, and that is exactly what we want it to do.
            let ok = back.map {
                $0.identifier == zone.identifier
                    || $0.secondsFromGMT(for: Self.now) == zone.secondsFromGMT(for: Self.now)
            } ?? false
            if !ok {
                violations.append(
                    "  \(id) renders as '\(label)' but resolve('\(label)') = "
                        + "\(back?.identifier ?? "nil") — a stamp we produced cannot be re-read by us"
                )
            }
        }
        finish("labels-reparse", violations, Set(TimeZoneAlias.map.values).count)
    }

    // MARK: - driver

    private func runProperty(
        _ name: String,
        _ body: (String, [TimeZone]) -> String?
    ) {
        var random = JavaRandom(seed: Self.seed)
        var violations: [String] = []

        for i in 0..<Self.cases {
            let input = generateInput(&random)
            let targets = Self.targetSets[random.nextInt(Self.targetSets.count)]
            if let result = body(input, targets), violations.count <= Self.maxReported {
                if violations.count == Self.maxReported {
                    violations.append("  ... more violations suppressed")
                } else {
                    let labels = targets.map { TimeZoneAlias.shortLabel(for: $0) }.joined(separator: ", ")
                    violations.append(
                        "  case #\(i)\n"
                            + "        input   \(show(input))\n"
                            + "        targets \(labels.isEmpty ? "(none)" : labels)\n"
                            + "        \(result)"
                    )
                }
            }
        }
        finish(name, violations, Self.cases)
    }

    private func finish(_ name: String, _ violations: [String], _ total: Int) {
        if violations.isEmpty {
            print("[property] \(name): \(total) cases, no counterexamples (seed \(Self.seed))")
            return
        }
        XCTFail(
            "PROPERTY VIOLATED: \(name) — \(violations.count) counterexample(s) in \(total) cases "
                + "(seed \(Self.seed))\n" + violations.joined(separator: "\n")
        )
    }

    // MARK: - generators (mirrors of the Kotlin ones, call-for-call)

    private func generateInput(_ random: inout JavaRandom) -> String {
        let prefix = Self.prefixes[random.nextInt(Self.prefixes.count)]
        let core = generateTimeExpression(&random)
        let suffix = Self.suffixes[random.nextInt(Self.suffixes.count)]
        let text = prefix + core + suffix
        // 1 in 12 inputs gets a stray astral character glued on, to keep the UTF-16
        // index arithmetic in splice() under pressure.
        return random.nextInt(12) == 0
            ? Self.astral[random.nextInt(Self.astral.count)] + text
            : text
    }

    private func generateTimeExpression(_ random: inout JavaRandom) -> String {
        if random.nextInt(10) == 0 {
            let keyword = random.nextBoolean() ? "noon" : "midnight"
            return keyword + zoneToken(&random)
        }
        let twelveHour = random.nextBoolean()
        let hour = twelveHour ? 1 + random.nextInt(12) : random.nextInt(24)
        var s = ""
        // Short-circuits exactly as Kotlin's `&&` does — nextBoolean() must not be
        // consumed when twelveHour is true, or the two sequences diverge here.
        if !twelveHour && random.nextBoolean() && hour < 10 { s += "0" }
        s += "\(hour)"
        if random.nextInt(3) != 0 {
            s += ":"
            let minute = random.nextInt(60)
            if minute < 10 { s += "0" }
            s += "\(minute)"
        }
        if twelveHour && random.nextInt(4) != 0 {
            s += Self.meridiems[random.nextInt(Self.meridiems.count)]
        }
        return s + zoneToken(&random)
    }

    private func zoneToken(_ random: inout JavaRandom) -> String {
        if random.nextInt(5) == 0 { return "" }
        let separator = Self.separators[random.nextInt(Self.separators.count)]
        return separator + Self.zoneTokens[random.nextInt(Self.zoneTokens.count)]
    }

    private func show(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\t": out += "\\t"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\"": out += "\\\""
            default:
                let v = u.value
                if v < 0x20 || v == 0x7F || v == 0xA0 || v > 0xFFFF {
                    out += String(format: "\\u{%04X}", v)
                } else {
                    out.unicodeScalars.append(u)
                }
            }
        }
        return out + "\""
    }

    // MARK: - constants (kept identical to PropertyTest.kt)

    static let cases = 500
    static let maxReported = 8
    static let seed: Int64 = 20260518

    /// Same fixed clock as EvalCorpusTests. Never `Date()`.
    static let now: Date = {
        var c = DateComponents()
        c.year = 2026; c.month = 5; c.day = 18; c.hour = 13; c.minute = 0
        c.timeZone = TimeZone(identifier: "UTC")
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: c)!
    }()

    static let zone = TimeZone(identifier: "America/New_York")!

    static let targetSets: [[TimeZone]] = [
        ["America/Chicago", "America/New_York", "America/Los_Angeles", "Europe/London"],
        ["America/New_York", "America/Los_Angeles"],
        ["America/New_York"],
        ["UTC", "Asia/Tokyo", "Asia/Kolkata"],
        ["America/Chicago", "America/New_York", "America/Los_Angeles"],
        [],
    ].map { $0.compactMap(TimeZone.init(identifier:)) }

    static let prefixes = [
        "", "", "meet at ", "lets do ", "call ", "sync ", "ping me by ", "deadline ",
        "room 5 is open, ", "part 3 then ", "9-5 job, ", "hola ", "\u{645}\u{631}\u{62D}\u{628}\u{627} ",
        "\u{1F389} ", "the top 5 est. results, ",
    ]

    /// The trailing five are combining/joining characters, and they sit here rather
    /// than in `astral` because *position* is the whole point: they land immediately
    /// after the match end, which is the one place a splice can round a UTF-16
    /// boundary into the middle of a grapheme cluster.
    ///
    /// Added 2026-07-25 after this core was found duplicating a character there —
    /// and after this very property test, freshly ported, failed to notice, because
    /// every astral case in the generator is a *prefix* and every suffix was an
    /// ordinary word. A detector that cannot reach the defect it was written for is
    /// not a detector. Both cores carry the same list so they keep exploring
    /// identical inputs.
    static let suffixes = [
        "", "", " tomorrow", " ok?", "!", ".", ", thanks", " sharp", "\n", " \u{1F680}",
        " and then dinner",
        "\u{0301}",  // combining acute
        "\u{200D}",  // zero-width joiner
        "\u{FE0F}",  // variation selector-16
        "\u{064B}",  // Arabic fathatan
        "\u{0E31}",  // Thai mai han akat
    ]

    static let meridiems = ["am", "pm", "AM", "PM", " am", " pm", "a.m.", "p.m.", " p.m."]

    static let separators = [" ", " ", "  ", "\t"]

    static let zoneTokens = [
        "et", "est", "edt", "ct", "cst", "cdt", "mt", "mst", "mdt", "pt", "pst", "pdt",
        "akst", "akdt", "hst", "utc", "gmt", "bst", "cet", "cest", "eet", "eest",
        "ist", "jst", "kst", "sgt", "hkt", "aest", "aedt", "nzst", "nzdt",
        "eastern", "central", "mountain", "pacific", "alaska", "hawaii",
        "london", "tokyo", "singapore", "sydney", "india",
        "ET", "CT", "PT", "UTC", "IST", "Pacific",
    ]

    static let astral = ["\u{1F389}", "\u{1F680}", "\u{1F44D}", "\u{1F600}\u{1F600}"]
}
