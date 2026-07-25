# TimeTwister

Converts timezone references inline, before you send. Type `5pm CT`, and TimeTwister expands it to `5pm CT (6pm ET · 3pm PT)` — in iMessage, WhatsApp, Signal, Telegram, Slack, anywhere you type.

This repo is a monorepo with three builds, all sharing one Kotlin/Swift parser core:

| Platform | Path | Entry point | Status |
|---|---|---|---|
| **Android** | [`android/`](android/) | `ACTION_PROCESS_TEXT` (selection toolbar) | Primary target — see [android/README.md](android/README.md) |
| **iOS** | [`ios/`](ios/) | Keyboard extension + Action Extension (share sheet) | Scaffold — see [ios/README.md](ios/README.md) |
| **Desktop** | [`desktop/`](desktop/) | System-tray "Convert clipboard" (Win/macOS/Linux) | See [desktop/README.md](desktop/README.md) |

The core logic (parser, alias table, stamp renderer) is duplicated in Swift and Kotlin, and the two test suites are meant to mirror each other so divergence gets caught early. Kotlin tests are JVM-only, so no emulator is needed.

They do not mirror each other today, and the two cores do **not** produce identical output for every input:

- The Kotlin suite is the larger of the two and covers cases the Swift one doesn't. Treat "mirrored suites" as the goal, not the current state.
- Both sides hard-code a short label (`ET`, `CT`, `PT`, …) for a common set of zones and agree there. For zones *outside* that set the two fall back differently — one may render the raw IANA id, the other a platform abbreviation or a bare `GMT+1`-style offset — so a stamp for, say, `Europe/Berlin` does not match across platforms. Converging the fallback is in progress on the Kotlin side; bringing Swift in line is a later pass.
- The Kotlin core also carries splice entry points the Swift core has no equivalent for, because the iOS Action Extension takes a different path.

## Demo

What you type, and what gets sent:

| You type | What gets sent |
|---|---|
| `lets do 5pm CT` | `lets do 5pm CT (6pm ET · 3pm PT)` |
| `how about 5:30pm pacific` | `how about 5:30pm PT (7:30pm CT · 8:30pm ET)` |
| `landing at 17:00 ET` | `landing at 5pm ET (4pm CT · 2pm PT)` |
| `call at noon CT` | `call at 12pm CT (1pm ET · 10am PT)` |
| `deploy at midnight ET` | `deploy at 12am ET (11pm CT · 9pm PT)` |
| `ship by 11pm utc` | `ship by 11pm UTC (6pm CT · 7pm ET · 4pm PT)` |
| `meet at 7pm ist tomorrow` | `meet at 7pm IST (8:30am CT · 9:30am ET · 6:30am PT) tomorrow` |
| `room 5 is open` | *(no detection — bare numbers without a TZ or am/pm are ignored)* |

Targets above are CT/ET/PT — these are the user-configurable set in the settings screen. Half-hour offset zones (India, Nepal, parts of Australia) are handled correctly because the converter uses IANA zone IDs, not fixed offsets, so DST transitions also do the right thing.

You can reproduce this locally without sideloading — with one caveat. `StampDemo` carries a class-level `@Ignore` so it stays out of normal test runs, which means the obvious command runs nothing and still exits green:

```bash
cd android
./gradlew :core:test --tests "*StampDemo*" --info   # reports "1 skipped", prints no table
```

To actually see the output, remove the `@Ignore` on `core/src/test/kotlin/com/timetwister/core/StampDemo.kt` first. (Making the demo opt-in via a system property instead of `@Ignore` would fix this properly; it hasn't been done yet.)

## Why both?

The project started as an iOS keyboard extension. Halfway through scaffolding it, the keyboard-switching UX on iOS became the blocker: users would have to swap from the system keyboard to TimeTwister and back for every conversion. Android has `ACTION_PROCESS_TEXT`, which removes the swap entirely — long-press the phrase, pick TimeTwister from the selection toolbar, done.

Android is now the primary build. The iOS scaffold is kept intact in case the UX can be made to work — the v0.4 share-sheet extension is that attempt, though it has yet to be run on a device. Shipping only the settings app plus a Shortcut is the fallback.

## Quick start

- Android: see [android/README.md](android/README.md). Runs on Windows / Linux / macOS, no Apple Dev membership needed.
- iOS: see [ios/README.md](ios/README.md). Needs a Mac + Xcode + Apple Dev Program for device install.

## Privacy

- All parsing and conversion happens on-device. No network calls.
- No keystroke logging, no analytics, no third-party SDKs.
- The Android build declares no Internet permission, so it *cannot* make a network call — that one is enforced by the OS, not by us. The iOS build declares no URL entitlements and makes no network calls, but iOS grants network access without a permission, so take that as a statement about the code rather than a sandbox guarantee.
- **The Android build does declare one permission: `READ_CONTACTS`.** It exists only for the optional v0.2 "Suggest from contacts" feature (Settings → *Suggest from contacts*), which reads phone-number country codes to propose target zones. It's a runtime permission, so nothing is read until you tap that button and grant it, and the app is fully usable if you never do. Numbers are read only to derive a country code; what gets persisted is the zone list you accept, not the contacts. With no Internet permission, nothing can be transmitted either way.

## Roadmap

Nothing here has been *distributed* yet. "Built" below means the code exists and builds; it does not mean anyone other than us has run it. Nothing is on the Play Store, nothing is on TestFlight, and no release tag has been cut — `versionCode` is still 1.

- **v0.1** — Android `ACTION_PROCESS_TEXT` + iOS keyboard extension. *(built. The Android path is exercised regularly; the iOS keyboard extension compiles but has never been installed on a device from this repo.)*
- **v0.2** — Smart defaults: infer target zones from contact history. *(built and working on Android — Settings → "Suggest from contacts" reads phone-number country codes and proposes zones. This is the one item here that does what it says. It requires the `READ_CONTACTS` runtime permission; see [Privacy](#privacy).)*
- **v0.3** — Play Store + TestFlight distribution. *(**not done.** `.github/workflows/android-release.yml` builds a signed APK + AAB on a `v*` tag and attaches them to a GitHub Release — that is as far as it goes. There is no Play publishing step and no Play service-account secret, so the Play upload is manual and has never happened. `ios-release.yml` is wired to archive and upload to TestFlight, but none of its six Apple secrets are configured and it has never completed a run.)*
- **v0.4** — iOS share-sheet extension. *(built, unverified — `TimeTwisterShareExtension` Action Extension, declared in `ios/project.yml`. iOS CI has never gone green, so this has never been built or run anywhere but a local Xcode session.)*
- **v0.5** — Desktop port. *(built — Kotlin/JVM tray app in `desktop/`, reuses the Android core sources directly. Builds and tests in CI; there is no installer, no packaging, and no distribution channel. macOS Catalyst still pending and tracked under iOS.)*

Next, in order: get iOS CI green, cut a real tag and confirm the release artifacts are installable, then decide whether Play is worth the $25 and the review cycle.

## License

MIT — see [LICENSE](LICENSE).
