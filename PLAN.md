# TimeTwister — what's left

Status as of **2026-07-25**, after the parser-hardening wave (`9021a73`).

The build works again on a clean Windows box, the shared core is hardened and
covered, and the README no longer claims things that aren't true. What remains is
listed below in the order it should be tackled, with the reason for that order.

**Nothing here is distributed.** No release tag exists, `versionCode` is still 1,
and the iOS pipeline has never gone green.

---

## Where things stand

| | State |
|---|---|
| Shared Kotlin core | Hardened. 100% precision/recall/exact on a 214-row graded corpus; 9 invariants enforced over 500 seeded cases each |
| Android app | Builds, installs, 16.3 MB debug APK. **Never run by a human** — all UX work is compile-verified only |
| Desktop tray | Builds, tests, `jpackage` app-image runs without a system JVM. Swing/tray wiring untested |
| iOS | Cannot be built or verified here. Swift core has **none** of the hardening below |
| CI | Android + desktop jobs sound. iOS almost certainly never passed (no schemes were declared until now, still unverified) |

Test loop (no Android SDK needed for the core):

```powershell
.\android\gradlew.bat -p desktop :core:test        # shared core + eval + properties
.\android\gradlew.bat -p android :app:assembleDebug
.\android\gradlew.bat -p desktop build             # desktop (also re-runs core)
```

---

## Ranked work

### 1. ~~Run the app on an emulator~~ — **DONE 2026-07-25: it works**

AVD `tw35` (API 35, Pixel 6, x86_64, hardware-accelerated) created and booted;
debug APK installed and driven via `adb`. **No crashes and no ANRs across every
path exercised.**

Verified on a real Android runtime, not by reasoning:

| path | result |
|---|---|
| First-run onboarding | Renders. The Compose-drawn toolbar mock shows `⋮` highlighted with TimeTwister *inside* the overflow — the step that used to be invisible |
| Read-only selection (`can we do 5pm CT`) | Toast: **"5pm CT (6pm ET · 4pm MT · 3pm PT) — copied (this text is read-only)"**. Previously a guaranteed silent no-op, and the most intuitive first thing a user tries |
| No detection (`lets meet at 5`) | Toast: **"No time found — try "5pm CT" or "17:00 ET""** — actionable, not generic |
| False positive (`use 12 pt font`) | Declines and says so. The headline P0, confirmed end-to-end |
| Live preview | Splices as you type: `lets do 5pm CT (6pm ET · 4pm MT · 3pm PT)` |
| Dark mode | Clean dark surfaces, and the launch window is dark — no white flash |

Screens: `01-firstrun`, `02-settings`, `04-readonly-toast`, `05-nodetect-toast`,
`06-falsepositive`, `07-darkmode`, `08-dark-settled`.

Relaunch the emulator with:

```powershell
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd tw35 -no-snapshot-load -gpu swiftshader_indirect
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Still unverified by hand: the zone-picker search, the contacts-permission denial
path, the >4-zone cap warning, and rotation state retention.

### 2. ~~Decide whether Swift can be verified on Windows~~ — **ANSWERED 2026-07-25: yes, with one fix**

Swift 6.3.3 installed to the user profile (no elevation). The **whole Swift core
compiles on Windows**, XCTest runs, and `CountryZoneTests` passes 7/7. Parsing,
alias resolution, `NSRegularExpression`, calendar maths and `TimeZone` all behave.

One blocker, isolated to a single line: **`DateFormatter.string(from:)` traps**
(illegal instruction, `0xC000001D`) in swift-corelibs-Foundation on Windows, with
or without an explicit locale. Every test that renders a time dies there.

Verified alternatives on the same input (Kolkata, 2026-01-15T13:00Z):

| approach | result |
|---|---|
| manual `Calendar` arithmetic | ✅ `6:30pm` |
| `Date.FormatStyle` | ✅ works |
| `DateFormatter` (+/- locale) | ❌ traps |

`DateFormatter` is used in exactly **one** place — `TimeConverter.formatted()`.
This is a corelibs-on-Windows limitation, **not an iOS bug**: Apple's Foundation
is a different implementation, so nothing is broken on device today.

- [x] Toolchain installed, core compiles, XCTest runs
- [ ] Replace `TimeConverter.formatted()` with manual arithmetic. Worth doing on
      its own merits: it drops a heavyweight locale-sensitive dependency for what
      is a trivial 12-hour render, matches how Kotlin pins the pattern, kills the
      classic `DateFormatter` locale bug class outright, and makes the Swift core
      testable on Windows **and** on a Linux CI runner instead of macOS only.
- [ ] Then run the full 28-case suite here and use it as the oracle for item 5

Repro (needs `vcvars64.bat` in the environment):

```powershell
$base = "$env:LOCALAPPDATA\Programs\Swift"
$env:PATH = "$base\Toolchains\6.3.3+Asserts\usr\bin;$base\Runtimes\6.3.3\usr\bin;$env:PATH"
$env:SDKROOT = "$base\Platforms\6.3.3\Windows.platform\Developer\SDKs\Windows.sdk"
swift test   # from a SwiftPM package wrapping ios/TimeTwisterCore
```

A permanent `Package.swift` in `ios/` would make this a one-command check for
everyone, and would let CI test the Swift core without a Mac.

### 3. Parser gaps that still mislead

Confirmed still-open behaviour, all verifiable locally, all drop into the
existing corpus + regression suite:

- [ ] **Ranges** — `3-5pm ET` converts only the last endpoint, so the stamp reads as
      if a two-hour window maps to a single time. *(Blocked on the A001 decision below.)*
- [ ] **`5.30pm`** — a dot is not accepted as a minute separator; silently does nothing
- [ ] **`1700 UTC`** — no military-time support at all
- [ ] **`half past 5`** — silently does nothing

### 4. Quick wins, one pass

- [ ] `StampDemo` is still `@Ignore`d, so the README's "reproduce this locally"
      command reports *1 skipped* and prints nothing. Gate it on a system property
      instead so `-Dtimetwister.demo=1` actually runs it.
- [ ] Promote the 5 `unverified` corpus rows — execution has since confirmed them
- [ ] `allowBackup="true"` with no `dataExtractionRules` / `fullBackupContent`
- [ ] App icon: add `roundIcon` and a `<monochrome>` layer (Android 13+ themed icons
      currently fall back). It also reads as "clock app", not "timezone converter"

### 5. Port the hardening to Swift — *position depends on item 2*

Verified today: the Swift core contains **zero** occurrences of `maybeSplice`, and
`shortLabel` still falls back to `tz.abbreviation()`. So iOS currently ships every
false-positive bug fixed on 2026-07-25 — `12 pt font`, the double-stamp nesting,
the separator/NBSP zone-drop, the DST-gap rewrite.

The gap widens with every core fix. Kotlin is the reference implementation now, so
this is translation against a test oracle rather than design work.

- [ ] `maybeSplice` + its read-only guard
- [ ] `shortLabel` fallback → instant-derived abbreviation, never a raw IANA id
- [ ] Disambiguator rule (am/pm or colon required)
- [ ] Stamp recognition / idempotence
- [ ] Separator + NBSP + newline handling
- [ ] DST-gap refusal
- [ ] Day markers (`+1d` / `-1d`)
- [ ] Port the corpus so both cores are scored by the same rows

### 6. `:app` unit tests — *decide after item 1*

The largest untested surface, but most of it is Android-framework-bound (Toast,
clipboard, intents). Honest coverage needs **Robolectric** (a new dependency —
your call) or instrumentation tests (which need item 1 anyway). Without one of
those, only a thin pure-logic slice is reachable, which is worth less than the
line count suggests.

### 7. Desktop Swing/tray seams

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
awaiting adjudication. `A001` gates the ranges work in item 3.

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

### D4. Signing material
Android keystore; six Apple secrets. Needed before anything reaches a person.

### D5. A Mac session
The only way to prove iOS CI works. Gates every iOS item.

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

- Cached zone list can go stale if the device zone changes and Settings is never opened
- Desktop has no autostart-on-login
- `values-night` only takes effect at API 29+; `minSdk` is 26
