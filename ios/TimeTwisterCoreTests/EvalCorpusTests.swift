//
//  EvalCorpusTests.swift
//  TimeTwisterCoreTests
//
//  Corpus-driven evaluation of the Swift parser + splicer, scored against the
//  SAME graded corpus as the Kotlin core.
//
//  There is deliberately no copy of the corpus in this directory. Both cores read
//  android/core/src/test/resources/eval/corpus.tsv, located relative to this
//  source file, so a row can never say one thing on Android and another on iOS.
//  Two copies of a golden file is two golden files, and the second one is wrong
//  the first time somebody edits only the first.
//
//  Determinism rules, all of which the corpus header restates:
//   - `now` is always NOW. We never call Date().
//   - target zones are always TARGETS.
//   - the process default zone is pinned for the duration of this class, because
//     TimeParser.detect() falls back to TimeZone.current for expressions with no
//     zone token and that seam is not injectable.
//
//  Only rows in BLOCKING_CATEGORIES can fail the build. Rows tagged `known_gap`,
//  `ambiguous` or `unverified` are scored and printed but never fail, so the
//  corpus can carry open questions and known defects without holding CI hostage.
//

import XCTest
@testable import TimeTwisterCore

// MARK: - one graded row

struct EvalRow {
    let id: String
    let input: String
    let expectDetect: Bool
    let expectOutput: String
    let category: String
    let note: String
    let line: Int
}

final class EvalCorpusTests: XCTestCase {

    // -----------------------------------------------------------------------
    // THE RATCHET.
    //
    // The minimum fraction of BLOCKING rows that must pass exactly (both the
    // detect decision and the rewritten string).
    //
    // If you are landing a change that knowingly regresses a blocking row, do
    // NOT lower this — move the row to `known_gap` with a note, so the defect
    // stays visible in the score.
    // -----------------------------------------------------------------------
    static let minBlockingPassRate = 1.0

    /// Categories whose rows are regressions-not-allowed. Everything else is report-only.
    static let blockingCategories = [
        "must_detect", "must_not_detect", "whitespace", "unicode", "boundary", "idempotent",
    ]

    /// Report-only categories: scored and printed, never fatal.
    static let reportingCategories = ["known_gap", "ambiguous", "unverified"]

    static var allCategories: [String] { blockingCategories + reportingCategories }

    /// Sentinel in the expect_output column meaning "the tool must not change this".
    static let sameAsInput = "=input"

    static let corpusPath = "android/core/src/test/resources/eval/corpus.tsv"

    /// Fixed clock. Never `Date()`. 2026-05-18 is deliberately mid-May: US DST on,
    /// UK on BST, Australia/NZ off DST, and it is far from every transition so the
    /// internal roll-forward-a-day heuristic cannot change an offset out from
    /// under an expectation. Same instant the Kotlin suite pins.
    static let now: Date = {
        var c = DateComponents()
        c.year = 2026; c.month = 5; c.day = 18; c.hour = 13; c.minute = 0
        c.timeZone = TimeZone(identifier: "UTC")
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        return cal.date(from: c)!
    }()

    /// Rendered, in this order, as CT / ET / PT / UK.
    static let targets: [TimeZone] = [
        TimeZone(identifier: "America/Chicago")!,
        TimeZone(identifier: "America/New_York")!,
        TimeZone(identifier: "America/Los_Angeles")!,
        TimeZone(identifier: "Europe/London")!,
    ]

    /// `TimeParser.detect` falls back to this for expressions with no zone token.
    ///
    /// Passed explicitly to every call below rather than installed as the process
    /// default. Kotlin pins the JVM default around its test class; the equivalent
    /// (`NSTimeZone.default = …`) is accepted and then **ignored** by
    /// swift-corelibs, so on this runner it left ~20 zone-less rows scored against
    /// the machine's own zone while looking pinned. A parameter cannot lie about
    /// having worked.
    static let pinnedDefaultZone = "America/New_York"

    static let defaultZone = TimeZone(identifier: pinnedDefaultZone)!

    // MARK: - shape

    func testCorpusIsWellFormed() throws {
        let rows = try Self.corpus()
        XCTAssertGreaterThanOrEqual(
            rows.count, 120,
            "corpus is suspiciously small (\(rows.count) rows); it should be the broad one"
        )

        var ids = Set<String>()
        var inputsPerCategory = Set<String>()
        for r in rows {
            XCTAssertTrue(ids.insert(r.id).inserted, "line \(r.line): duplicate id '\(r.id)'")
            XCTAssertTrue(
                Self.allCategories.contains(r.category),
                "line \(r.line): row \(r.id) has unknown category '\(r.category)'"
            )
            XCTAssertTrue(
                r.expectDetect || r.expectOutput == r.input,
                "line \(r.line): row \(r.id) expects no detection, so expect_output must be "
                    + "'\(Self.sameAsInput)'"
            )
            XCTAssertTrue(
                inputsPerCategory.insert(r.category + " " + r.input).inserted,
                "line \(r.line): row \(r.id) duplicates another row's input within category "
                    + "'\(r.category)'"
            )
        }

        for c in Self.blockingCategories {
            XCTAssertTrue(
                rows.contains { $0.category == c },
                "blocking category '\(c)' has no rows; either populate it or drop it"
            )
        }
    }

    // MARK: - the measurement

    func testCorpusScore() throws {
        let rows = try Self.corpus()

        var overall = Tally()
        var blocking = Tally()
        var byCategory: [String: Tally] = [:]
        for c in Self.allCategories { byCategory[c] = Tally() }

        var blockingFailures: [String] = []
        var reportedFailures: [String] = []

        for r in rows {
            let actualDetect = !TimeParser
                .detect(in: r.input, defaultZone: Self.defaultZone)
                .isEmpty
            let actualOutput = TimeConverter.splice(
                input: r.input,
                targets: Self.targets,
                now: Self.now,
                defaultZone: Self.defaultZone
            )
            let outputOk = actualOutput == r.expectOutput
            let pass = (actualDetect == r.expectDetect) && outputOk

            overall.record(r, actualDetect, outputOk, pass)
            byCategory[r.category]?.record(r, actualDetect, outputOk, pass)

            let isBlocking = Self.blockingCategories.contains(r.category)
            if isBlocking { blocking.record(r, actualDetect, outputOk, pass) }

            if !pass {
                let text = describe(r, actualDetect, actualOutput)
                if isBlocking { blockingFailures.append(text) } else { reportedFailures.append(text) }
            }
        }

        print(report(rows, overall, blocking, byCategory, blockingFailures, reportedFailures))

        XCTAssertGreaterThanOrEqual(
            blocking.passRate, Self.minBlockingPassRate - 1e-9,
            "EVAL GATE FAILED: blocking pass rate \(pct(blocking.passRate)) < required "
                + "\(pct(Self.minBlockingPassRate)) (\(blocking.rowPass)/\(blocking.total) rows). "
                + "\(blockingFailures.count) blocking row(s) failed; see the EVAL REPORT above."
        )
    }

    // MARK: - loading

    private static var cached: [EvalRow]?

    /// The corpus lives in the Android tree because that is where it was born and
    /// where the reference implementation is scored. Located relative to this
    /// source file rather than bundled, so there is exactly one copy.
    private static func corpusURL() -> URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // TimeTwisterCoreTests
            .deletingLastPathComponent()   // ios
            .deletingLastPathComponent()   // repo root
            .appendingPathComponent(corpusPath)
    }

    static func corpus() throws -> [EvalRow] {
        if let cached { return cached }

        let url = corpusURL()
        guard FileManager.default.fileExists(atPath: url.path) else {
            // Running from a bundle that isn't next to the repo — a simulator or
            // device test run. The corpus is a build-machine measurement; skip
            // loudly rather than fail.
            throw XCTSkip("eval corpus not reachable at \(url.path) — run from a source checkout")
        }

        let text = try String(contentsOf: url, encoding: .utf8)
        if text.contains("\u{FFFD}") {
            throw CorpusError.notUtf8
        }

        var rows: [EvalRow] = []
        var lineNumber = 0
        var headerSeen = false

        // Split on `isNewline`, NOT `components(separatedBy: "\n")`.
        //
        // Swift treats CRLF as a *single* Character, so searching a CRLF file for
        // "\n" finds nothing at all and the entire corpus comes back as one line —
        // which, since line 1 is a comment, silently yielded "no header row"
        // rather than any hint that the file had been read wrong. `isNewline` is
        // true for the CRLF cluster itself, so this splits correctly on either
        // convention and keeps `lineNumber` aligned with the file.
        for rawLine in text.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline) {
            lineNumber += 1
            var line = String(rawLine)
            if lineNumber == 1, line.hasPrefix("\u{FEFF}") { line.removeFirst() }
            if line.trimmingCharacters(in: .whitespaces).isEmpty || line.hasPrefix("#") { continue }

            let columns = line.components(separatedBy: "\t")
            if !headerSeen {
                headerSeen = true
                guard columns.count == 6, columns[0] == "id" else {
                    throw CorpusError.badHeader(line: lineNumber, got: line)
                }
                continue
            }
            guard columns.count == 6 else {
                throw CorpusError.badColumnCount(line: lineNumber, got: columns.count)
            }

            let input = unescape(columns[1])
            let expectDetect: Bool
            switch columns[2] {
            case "yes": expectDetect = true
            case "no": expectDetect = false
            default: throw CorpusError.badDetect(line: lineNumber, got: columns[2])
            }
            let expectOutput = columns[3] == sameAsInput ? input : unescape(columns[3])

            rows.append(EvalRow(
                id: columns[0], input: input, expectDetect: expectDetect,
                expectOutput: expectOutput, category: columns[4], note: columns[5],
                line: lineNumber
            ))
        }
        guard headerSeen else { throw CorpusError.noHeader }

        cached = rows
        return rows
    }

    enum CorpusError: Error, CustomStringConvertible {
        case notUtf8
        case noHeader
        case badHeader(line: Int, got: String)
        case badColumnCount(line: Int, got: Int)
        case badDetect(line: Int, got: String)

        var description: String {
            switch self {
            case .notUtf8:
                return "corpus contains U+FFFD — it was not saved as UTF-8. The expected outputs "
                    + "contain U+00B7 MIDDLE DOT; re-save corpus.tsv as UTF-8 without a BOM."
            case .noHeader:
                return "corpus has no header row."
            case .badHeader(let line, let got):
                return "line \(line): expected the 6-column header row starting with 'id', got: \(got)"
            case .badColumnCount(let line, let got):
                return "line \(line): expected 6 tab-separated columns, got \(got). A field "
                    + "probably contains a literal tab (write it as \\t)."
            case .badDetect(let line, let got):
                return "line \(line): expect_detect must be 'yes' or 'no', got '\(got)'"
            }
        }
    }

    /// Decode the corpus escape vocabulary: `\t` `\n` `\r` `\\` and `\uXXXX`.
    ///
    /// Works in UTF-16 code units, not Characters, because astral characters are
    /// written in the corpus as two consecutive `\uXXXX` surrogate escapes. Decoding
    /// per-Character would produce two unpaired surrogates instead of one emoji.
    static func unescape(_ raw: String) -> String {
        guard raw.contains("\\") else { return raw }
        let src = Array(raw.utf16)
        var out: [UInt16] = []
        out.reserveCapacity(src.count)

        var i = 0
        while i < src.count {
            let c = src[i]
            guard c == 0x5C, i < src.count - 1 else {   // backslash
                out.append(c)
                i += 1
                continue
            }
            switch src[i + 1] {
            case 0x74: out.append(0x09); i += 2   // \t
            case 0x6E: out.append(0x0A); i += 2   // \n
            case 0x72: out.append(0x0D); i += 2   // \r
            case 0x5C: out.append(0x5C); i += 2   // \\
            case 0x75:                            // \uXXXX
                let hexUnits = (i + 5 < src.count) ? Array(src[(i + 2)...(i + 5)]) : []
                let hex = String(utf16CodeUnits: hexUnits, count: hexUnits.count)
                if let code = UInt16(hex, radix: 16) {
                    out.append(code)
                    i += 6
                } else {
                    out.append(c)
                    i += 1
                }
            default:
                out.append(c)
                i += 1
            }
        }
        return String(utf16CodeUnits: out, count: out.count)
    }
}

// MARK: - scoring

/// Confusion matrix + exact-match counters for a slice of the corpus.
private struct Tally {
    var truePositives = 0
    var falsePositives = 0
    var falseNegatives = 0
    var trueNegatives = 0
    var exactOutput = 0
    var rowPass = 0
    var total = 0

    /// An empty slice scores 1.0 rather than NaN so the report stays readable.
    private func ratio(_ n: Int, _ d: Int) -> Double { d == 0 ? 1.0 : Double(n) / Double(d) }

    var precision: Double { ratio(truePositives, truePositives + falsePositives) }
    var recall: Double { ratio(truePositives, truePositives + falseNegatives) }
    var exactRate: Double { ratio(exactOutput, total) }
    var passRate: Double { ratio(rowPass, total) }
    var f1: Double {
        let (p, r) = (precision, recall)
        return (p + r == 0) ? 0 : 2 * p * r / (p + r)
    }

    mutating func record(_ row: EvalRow, _ actualDetect: Bool, _ outputOk: Bool, _ pass: Bool) {
        total += 1
        if row.expectDetect && actualDetect { truePositives += 1 }
        if !row.expectDetect && actualDetect { falsePositives += 1 }
        if row.expectDetect && !actualDetect { falseNegatives += 1 }
        if !row.expectDetect && !actualDetect { trueNegatives += 1 }
        if outputOk { exactOutput += 1 }
        if pass { rowPass += 1 }
    }
}

// MARK: - reporting

private func pct(_ v: Double) -> String {
    let s = String(format: "%.2f", v * 100) + "%"
    return String(repeating: " ", count: max(0, 7 - s.count)) + s
}

private func pad(_ s: String, _ w: Int) -> String {
    s.count >= w ? s : s + String(repeating: " ", count: w - s.count)
}

/// Quote a string and make invisible characters visible, so a diff in the log is readable.
private func show(_ s: String) -> String {
    var out = "\""
    for u in s.unicodeScalars {
        switch u {
        case "\t": out += "\\t"
        case "\n": out += "\\n"
        case "\r": out += "\\r"
        case "\"": out += "\\\""
        case "\\": out += "\\\\"
        default:
            let v = u.value
            if v < 0x20 || v == 0x7F || v == 0xA0 || v == 0x202F || v == 0x200B || v == 0xFEFF {
                out += String(format: "\\u%04X", v)
            } else {
                out.unicodeScalars.append(u)
            }
        }
    }
    return out + "\""
}

private func describe(_ row: EvalRow, _ actualDetect: Bool, _ actualOutput: String) -> String {
    var s = "  [\(row.id)] \(row.category)  (corpus line \(row.line))\n"
    s += "      input    \(show(row.input))\n"
    if actualDetect != row.expectDetect {
        s += "      detect   expected=\(row.expectDetect ? "yes" : "no")"
        s += "  actual=\(actualDetect ? "yes" : "no")\n"
    }
    if actualOutput != row.expectOutput {
        s += "      expected \(show(row.expectOutput))\n"
        s += "      actual   \(show(actualOutput))\n"
    }
    if !row.note.isEmpty { s += "      note     \(row.note)\n" }
    return s
}

private func report(
    _ rows: [EvalRow],
    _ overall: Tally,
    _ blocking: Tally,
    _ byCategory: [String: Tally],
    _ blockingFailures: [String],
    _ reportedFailures: [String]
) -> String {
    let rule = String(repeating: "=", count: 78)
    var s = "\n\(rule)\nTIMETWISTER EVAL REPORT (swift core)\n\(rule)\n"
    s += "corpus            \(EvalCorpusTests.corpusPath)  (\(rows.count) rows)\n"
    s += "clock             2026-05-18T09:00:00-04:00[America/New_York]\n"
    s += "targets           "
    s += EvalCorpusTests.targets.map { TimeZoneAlias.shortLabel(for: $0) }.joined(separator: ", ")
    s += "\nsystem zone       \(EvalCorpusTests.pinnedDefaultZone)  (pinned for this class)\n"
    s += "\(rule)\n"

    s += "DETECT DECISION (positive class = \"this text contains a time\")\n"
    s += "                    all rows        blocking rows only\n"
    s += "  true positives  \(pad("\(overall.truePositives)", 16))\(blocking.truePositives)\n"
    s += "  false positives \(pad("\(overall.falsePositives)", 16))\(blocking.falsePositives)"
    s += "   <- each one is a visibly broken message\n"
    s += "  false negatives \(pad("\(overall.falseNegatives)", 16))\(blocking.falseNegatives)\n"
    s += "  true negatives  \(pad("\(overall.trueNegatives)", 16))\(blocking.trueNegatives)\n"
    s += "  precision       \(pad(pct(overall.precision), 16))\(pct(blocking.precision))\n"
    s += "  recall          \(pad(pct(overall.recall), 16))\(pct(blocking.recall))\n"
    s += "  F1              \(pad(pct(overall.f1), 16))\(pct(blocking.f1))\n\n"
    s += "REWRITE (exact string match on TimeConverter.splice)\n"
    s += "  exact match     \(pad(pct(overall.exactRate), 16))\(pct(blocking.exactRate))\n"
    s += "  row pass        \(pad(pct(overall.passRate), 16))\(pct(blocking.passRate))"
    s += "  <- the gated number\n\(rule)\n"

    s += "BY CATEGORY\n"
    s += "  category          gate        pass       exact   P        R        rows\n"
    for c in EvalCorpusTests.allCategories {
        guard let t = byCategory[c], t.total > 0 else { continue }
        let gate = EvalCorpusTests.blockingCategories.contains(c) ? "BLOCKING" : "report"
        s += "  \(pad(c, 18))\(pad(gate, 12))\(pad(pct(t.passRate), 11))\(pad(pct(t.exactRate), 11))"
        s += "\(pad(pct(t.precision), 9))\(pad(pct(t.recall), 9))\(t.rowPass)/\(t.total)\n"
    }
    s += "\(rule)\n"

    if !reportedFailures.isEmpty {
        s += "OPEN ITEMS (\(reportedFailures.count)) - reported, NOT fatal. known_gap = a real "
        s += "defect the corpus has\nalready described; ambiguous = needs an owner decision; "
        s += "unverified = needs a\nreal run to confirm the expected value.\n\n"
        for f in reportedFailures { s += f + "\n" }
        s += "\(rule)\n"
    }

    if !blockingFailures.isEmpty {
        s += "BLOCKING FAILURES (\(blockingFailures.count)) - these fail the build\n\n"
        for f in blockingFailures { s += f + "\n" }
        s += "\(rule)\n"
    }

    s += "GATE  blocking row-pass \(pct(blocking.passRate))"
    s += "  required >= \(pct(EvalCorpusTests.minBlockingPassRate))\n"
    s += "      ratchet: EvalCorpusTests.minBlockingPassRate\n\(rule)\n"
    return s
}
