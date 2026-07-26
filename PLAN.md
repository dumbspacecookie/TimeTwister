# TimeTwister — what's left

Status as of **2026-07-25**, after the Swift port wave.

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
| Shared Kotlin core | Hardened. 100% precision/recall/exact on the 214-row graded corpus; 9 invariants enforced over 500 seeded cases each |
| Shared Swift core | **Now at parity.** Same corpus, same 100%; same red-team suite. Builds and tests without a Mac |
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

### 5. Parser gaps that still mislead — **next**

Confirmed still-open behaviour, all verifiable locally on both cores now, all
drop into the existing corpus + regression suites:

- [ ] **Ranges** — `3-5pm ET` converts only the last endpoint, so the stamp reads as
      if a two-hour window maps to a single time. *(Blocked on A001 below.)*
- [ ] **`5.30pm`** — a dot is not accepted as a minute separator; silently does nothing
- [ ] **`1700 UTC`** — no military-time support at all
- [ ] **`half past 5`** — silently does nothing

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

- Cached zone list can go stale if the device zone changes and Settings is never opened
- Desktop has no autostart-on-login
- `values-night` only takes effect at API 29+; `minSdk` is 26
