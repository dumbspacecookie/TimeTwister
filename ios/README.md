# TimeTwister

An iOS custom keyboard that converts timezone references inline, before you send. Type `5pm CT`, tap a suggestion, and it expands to `5pm CT (6pm ET · 3pm PT)` — works in iMessage, WhatsApp, Signal, Telegram, Slack, anywhere you type.

## Why a keyboard (and not an iMessage app)

- Works across **every** messaging app, not just iMessage
- Recipients see normal text — they don't need to install anything
- Trade-off: you can't auto-convert to the *recipient's* TZ (no messaging app exposes that), so TimeTwister appends a short multi-TZ stamp that covers your configured set

## Project layout

```
timetwister/
├── README.md
├── project.yml                     # XcodeGen spec — generates .xcodeproj
├── TimeTwister/                    # Container app (settings UI)
│   ├── App.swift
│   ├── SettingsView.swift
│   └── Info.plist
├── TimeTwisterKeyboard/            # Keyboard extension target
│   ├── KeyboardViewController.swift
│   ├── SuggestionBar.swift
│   └── Info.plist
├── TimeTwisterCore/                # Shared logic (linked into both targets)
│   ├── TimeZoneAlias.swift         # "CT" / "pacific" / "EST" → IANA
│   ├── TimeParser.swift            # regex-based detector
│   ├── TimeConverter.swift         # formatting + multi-TZ rendering
│   └── UserPreferences.swift       # shared via App Group
└── TimeTwisterCoreTests/
    └── TimeConverterTests.swift
```

## Setup on macOS

1. Install XcodeGen: `brew install xcodegen`
2. From this directory: `xcodegen generate`
3. Open `TimeTwister.xcodeproj` in Xcode
4. Set your team under Signing & Capabilities for both targets
5. Run the container app once on your device to register the App Group
6. Settings → General → Keyboard → Keyboards → Add New Keyboard → TimeTwister → enable "Allow Full Access" (needed to read pasteboard/configuration; see privacy note below)

## CI (build without a Mac)

`.github/workflows/ci.yml` runs on `macos-14` GitHub runners and builds + tests every push. No secrets needed — it builds for the Simulator with signing disabled. This is useful to catch errors while you're iterating on a Windows machine; it will not produce an installable IPA (unsigned IPAs won't load onto a device).

## Distribution to friends

1. Apple Developer Program: $99/yr (required for TestFlight)
2. Archive → Distribute → App Store Connect → TestFlight
3. Add external testers via public link, up to 10,000
4. Rebuild every 90 days (TestFlight build expiry)

### TestFlight from CI (optional)

`.github/workflows/release.yml` archives + uploads to TestFlight when you push a tag like `v0.1.0`. Once set up you never need a Mac again for releases. Secrets required in GitHub repo settings:

| Secret | Where to get it |
|---|---|
| `APPLE_TEAM_ID` | developer.apple.com → Membership → Team ID |
| `BUILD_CERTIFICATE_BASE64` | Export your Apple Distribution cert from Keychain as `.p12`, then `base64 -i cert.p12 \| pbcopy` |
| `BUILD_CERTIFICATE_PASSWORD` | The password you set when exporting the `.p12` |
| `APPSTORE_KEY_ID` | App Store Connect → Users and Access → Keys → the Key ID |
| `APPSTORE_ISSUER_ID` | Same page, top of the Keys tab |
| `APPSTORE_PRIVATE_KEY` | The `.p8` file contents from that same Keys page (download is one-time) |

Create the App Store Connect API key with "App Manager" access. First-time setup still needs a Mac to export the signing cert; after that, releases are push-a-tag.

## Privacy posture

- "Allow Full Access" is required only because iOS keyboards can't access `UserDefaults` in an App Group without it. We don't log keystrokes, don't make network requests, and don't use third-party SDKs.
- All parsing and conversion happens on-device.

## Roadmap

- v0.1: Core parser + suggestion bar in keyboard + basic settings
- v0.2: Smart defaults (infer TZs from your contact history — requires Contacts permission, optional)
- v0.3: macOS keyboard (via Mac Catalyst or a separate AppKit target)
- v0.4: Recipient-specific TZ via share-sheet extension (one-tap "convert for Alex" before sending)
