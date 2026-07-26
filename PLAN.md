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
| Shared Kotlin core | 236-row graded corpus, blocking gate 100%, overall 99.15% (one deliberate open gap); 9 invariants over 500 seeded cases each |
| Shared Swift core | Same corpus, same gate, same red-team suite, 3 of the 9 invariants. Builds and tests without a Mac. **Not fully at parity** — see below |
| Android app | Builds, installs, runs on a real AVD. Most UX paths hand-verified (below) |
| Desktop tray | Builds, tests, `jpackage` app-image runs without a system JVM. Swing/tray wiring untested |
| iOS app | Core is verified; the app, keyboard and share extension still need a Mac |
| CI | Android + desktop jobs sound. iOS almost certainly never passed (no schemes were declared until recently, still unverified) |

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

- [ ] **The parser is O(n²) and only Android caps its input.** Two full-string
      copies run once per match (`TimeParser` unknown-zone lookahead and
      `stampRanges`). Measured: 200 KB of stamped text takes 34 s. Android caps
      at 5000 chars before parsing and is safe; the **iOS Action Extension, the
      iOS keyboard (main thread, every keystroke) and the desktop tray are all
      uncapped**. An extension that blocks for tens of seconds is watchdog-killed.
      Two independent fixes: port `MAX_INPUT_CHARS`, and pass explicit ranges to
      the regex instead of materialising substrings (makes both paths linear).
- [ ] **Autumn fall-back is unhandled on both cores.** An ambiguous wall clock
      (1:30am ET on 2026-11-01 happens twice) silently resolves to the earlier
      instant, with no marker and no refusal. `isUnrepresentable` structurally
      cannot fire — an ambiguous time round-trips perfectly. Needs an owner
      decision (D6).
- [ ] **ICU `\b` ≠ Java `\b`.** Swift misses ~6 detections Kotlin makes where a
      combining mark or ZWJ precedes the time, because ICU counts those as word
      characters. The `isNumber`/`isDigit` half of this divergence is fixed; this
      half is not.
- [ ] `5pm ET/PT` leaves `/PT` dangling — `tzPair` lists only DST-pair
      abbreviations, not the bare region tokens.

**Eval integrity** — the gate works (proved by mutation on both cores) but is
defensible in only one direction:

- [ ] `ratio(n, 0)` returns 1.0, so **a corpus that loaded zero blocking rows
      passes**. In Swift both eval tests share the `XCTSkip` path, so one missing
      file disarms the guard and the gate together.
- [ ] The ratchet is bypassable without touching the constant: recategorise a row
      to `ambiguous`, delete it (94 of 236 can go — the only floor is `>= 120`),
      or edit `expect_output` to match new behaviour. A pinned row count, pinned
      per-category counts, and `assert blocking.falsePositives == 0` would each
      close a route.
- [ ] Three of the four defects in §5 still have **no corpus row**, and
      `half past 5 ET` is encoded as `must_not_detect`, blocking, passing — a
      defect this file calls open is ratchet-protected as correct (D7).
- [ ] Harness config (`blockingCategories`, the ratchet, the clock, the targets)
      is duplicated per core with nothing asserting the copies agree.
- [ ] Swift is missing 6 of the 9 invariants: `source-label-first`,
      `distinct-targets`, `round-trip`, `whitespace-stable`,
      `no-detect-identity`, `never-throws`.
- [ ] `android/app` has **no `src/test` and no `androidTest`** — CI's
      `:app:testDebugUnitTest` passes vacuously. CI never runs `swift test`
      either. Cheapest first test: `UserPreferences.decode/encode`, where
      `defaults.size <= MAX_RECOMMENDED_ZONES` **fails today** outside the US.

**Security / privacy** (nothing critical; full details in the review)

- [ ] Read-only path writes the whole converted message to the clipboard without
      `ClipDescription.EXTRA_IS_SENSITIVE`, so Android 13+ shows a content
      preview.
- [ ] README Privacy says "no network calls" and does not mention that settings
      now leave the device via Android Auto Backup. True as written; incomplete.
- [ ] `android-release.yml` interpolates a tag name unquoted into a `run:` block
      that holds the signing keystore. `ios-release.yml` already does this
      correctly — copy that.

**UX**, from a real emulator session (everything changed in the icon/backup work
verified PASS; these are pre-existing)

- [ ] 🔴 **Intermittent stale settings UI.** Once in four, adding a zone
      persisted to both stores but the screen did not update — reads to the user
      as "Add timezone silently does nothing". Confirmed against the
      accessibility tree, not pixels. Likely `targetZonesFlow()` allocating a new
      Flow per recomposition and re-keying `collectAsState`; hoisting it into a
      `remember` closes the window.
- [ ] Launcher icon has **zero safe-zone margin** — the clock ring sits exactly
      on the mask boundary, so it reads as a bordered tile and will clip under
      any mask tighter than Pixel's circle.
- [ ] Zone picker is unusable in landscape with the keyboard up: the IME covers
      the result list *and* Cancel.
- [ ] Every search result is listed twice (once under "Common", once under "All").
- [ ] The false-positive decline reuses the no-detection string, so `use 12 pt
      font` invites a retry instead of saying it declined.
- [ ] No prominent disclosure before the contacts permission dialog (bears on D1).

### 5. Parser gaps that still mislead

Confirmed still-open behaviour, all verifiable locally on both cores now, all
drop into the existing corpus + regression suites:

- [ ] **Ranges** — `3-5pm ET` converts only the last endpoint, so the stamp reads as
      if a two-hour window maps to a single time. *(Blocked on A001 below.)*
- [ ] **`5.30pm`** — a dot is not accepted as a minute separator; silently does nothing
- [ ] **`1700 UTC`** — no military-time support at all
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
