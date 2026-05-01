# TimeTwister

Converts timezone references inline, before you send. Type `5pm CT`, and TimeTwister expands it to `5pm CT (6pm ET · 3pm PT)` — in iMessage, WhatsApp, Signal, Telegram, Slack, anywhere you type.

This repo is a monorepo with two builds:

| Platform | Path | Entry point | Status |
|---|---|---|---|
| **Android** | [`android/`](android/) | `ACTION_PROCESS_TEXT` (selection toolbar) | Primary target — see [android/README.md](android/README.md) |
| **iOS** | [`ios/`](ios/) | Keyboard extension | Scaffold — see [ios/README.md](ios/README.md) |

The core logic (parser, alias table, stamp renderer) is duplicated in Swift and Kotlin — the two cores produce identical output for the same input, and their test suites mirror each other so divergence is caught early (16 tests per side, JVM-only on the Kotlin side so no emulator needed).

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

You can reproduce this locally without sideloading: `gradle :core:test --tests "*StampDemo*" --info` from `android/`.

## Why both?

The project started as an iOS keyboard extension. Halfway through scaffolding it, the keyboard-switching UX on iOS became the blocker: users would have to swap from the system keyboard to TimeTwister and back for every conversion. Android has `ACTION_PROCESS_TEXT`, which removes the swap entirely — long-press the phrase, pick TimeTwister from the selection toolbar, done.

Android is now the primary build. The iOS scaffold is kept intact in case the UX can be made to work (e.g. moving to a share-sheet extension in v0.4, or shipping only the settings app and a Shortcut).

## Quick start

- Android: see [android/README.md](android/README.md). Runs on Windows / Linux / macOS, no Apple Dev membership needed.
- iOS: see [ios/README.md](ios/README.md). Needs a Mac + Xcode + Apple Dev Program for device install.

## Privacy

- All parsing and conversion happens on-device. No network calls.
- No keystroke logging, no analytics, no third-party SDKs.
- Both builds ship without the Internet permission (Android) and without any URL entitlements (iOS).

## Roadmap

- **v0.1** — Android `ACTION_PROCESS_TEXT` + iOS keyboard extension. *(current)*
- **v0.2** — Smart defaults: infer target zones from contact history (Android first — contact API is freer).
- **v0.3** — Play Store + TestFlight distribution.
- **v0.4** — iOS share-sheet extension (removes the keyboard-swap problem on iOS).
- **v0.5** — Desktop port (macOS via Catalyst, Windows via plain Kotlin/JVM tray app sharing the `core` module).

## License

MIT — see [LICENSE](LICENSE).
