# TimeTwister — iOS

Two iOS entry points share the same `TimeTwisterCore` parser:

1. **Custom keyboard** (`TimeTwisterKeyboard`) — converts time references inline as you type. Works in any app. Trade-off: user must switch from system keyboard to TimeTwister for each conversion + grant "Allow Full Access".
2. **Action Extension** (`TimeTwisterShareExtension`) — long-press / select text in any cooperating host (Notes, Mail, Safari, Messages-with-bridge apps), hit *Share* → **TimeTwister**, and the selection comes back with the stamp spliced in. No keyboard swap, no Full Access toggle. This is the closest iOS analogue to Android's `ACTION_PROCESS_TEXT`.

## Why a keyboard (and not an iMessage app)

- Works across **every** messaging app, not just iMessage
- Recipients see normal text — they don't need to install anything
- Trade-off: you can't auto-convert to the *recipient's* TZ (no messaging app exposes that), so TimeTwister appends a short multi-TZ stamp that covers your configured set

## Project layout

```
ios/
├── README.md
├── project.yml                     # XcodeGen spec — generates .xcodeproj + Info.plists
├── TimeTwister/                    # Container app (settings UI)
│   ├── App.swift
│   ├── SettingsView.swift
│   └── TimeTwister.entitlements    # App Group
├── TimeTwisterKeyboard/            # Keyboard extension target
│   ├── KeyboardViewController.swift
│   ├── SuggestionBar.swift
│   └── TimeTwisterKeyboard.entitlements
├── TimeTwisterCore/                # Shared logic (linked into both targets)
│   ├── TimeZoneAlias.swift         # "CT" / "pacific" / "EST" → IANA
│   ├── TimeParser.swift            # regex-based detector
│   ├── TimeConverter.swift         # formatting + multi-TZ rendering
│   └── UserPreferences.swift       # shared via App Group
└── TimeTwisterCoreTests/
    └── TimeConverterTests.swift
```

`Info.plist` files are generated from `project.yml` on `xcodegen generate` — they're not committed to the repo.

## Setup on macOS

1. Install XcodeGen: `brew install xcodegen`
2. From this directory: `xcodegen generate`
3. Open `TimeTwister.xcodeproj` in Xcode
4. Set your team under Signing & Capabilities for both targets
5. Run the container app once on your device to register the App Group
6. Settings → General → Keyboard → Keyboards → Add New Keyboard → TimeTwister → enable "Allow Full Access" (needed to read pasteboard/configuration; see privacy note below)

## CI (build without a Mac)

`.github/workflows/ci.yml` runs the iOS job on `macos-15` GitHub runners and builds + tests every push. No secrets needed — it builds for the Simulator with signing disabled. This is useful to catch errors while you're iterating on a Windows machine; it will not produce an installable IPA (unsigned IPAs won't load onto a device).

**This job has never gone green.** `project.yml` declared no `schemes:` block, so XcodeGen generated no schemes and every `xcodebuild -scheme ...` invocation failed before it compiled anything. A `schemes:` block now exists and CI selects the core tests with `-only-testing:TimeTwisterCoreTests`, but that has not yet been confirmed against a real runner — treat the iOS pipeline as unproven until you've seen a green run.

## Distribution to friends

1. Apple Developer Program: $99/yr (required for TestFlight)
2. Archive → Distribute → App Store Connect → TestFlight
3. Add external testers via public link, up to 10,000
4. Rebuild every 90 days (TestFlight build expiry)

### TestFlight from CI (not done)

`.github/workflows/ios-release.yml` is wired to archive + upload to TestFlight on a `v*` tag, but none of its six Apple secrets are configured, so it has never completed a run and nothing has ever reached TestFlight. This build is sideload-only for now. Setting it up needs an Apple Developer Program membership ($99/yr) and a one-time Mac session to export the signing cert; the full secret list is in the header of that workflow file.

(The old single `release.yml` was split into `android-release.yml` and `ios-release.yml`; references to `release.yml` elsewhere are stale.)

## Privacy posture

- "Allow Full Access" is required only because iOS keyboards can't access `UserDefaults` in an App Group without it. We don't log keystrokes, don't make network requests, and don't use third-party SDKs.
- All parsing and conversion happens on-device.

## Roadmap

iOS-local milestones. These are numbered independently of the project-level roadmap in the [root README](../README.md) — don't read `v0.3` here as the same thing as `v0.3` there.

- v0.1: Core parser + suggestion bar in keyboard + basic settings — *built, never installed on a device from this repo*
- v0.2: Smart defaults (infer TZs from your contact history — requires Contacts permission, optional) — *not started on iOS; exists on Android only*
- v0.3: macOS keyboard (via Mac Catalyst or a separate AppKit target) — *not started*
- v0.4: Recipient-specific TZ via share-sheet extension (one-tap "convert for Alex" before sending) — *the `TimeTwisterShareExtension` target exists and declares the activation rule; the recipient-picking part does not*
