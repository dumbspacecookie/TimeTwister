# TimeTwister — Android

Android build of TimeTwister. Uses `ACTION_PROCESS_TEXT` as the primary entry point — the user long-presses a time reference in any app (WhatsApp, Signal, Messages, Telegram, Discord, …), picks **TimeTwister** from the selection toolbar, and the stamp gets spliced into the selection inline.

No keyboard switching, no extra permissions, no accessibility service.

## Module layout

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── core/                          # pure Kotlin/JVM — no Android deps
│   └── src/main/kotlin/com/timetwister/core/
│       ├── DetectedTime.kt
│       ├── TimeZoneAlias.kt       # "CT" / "pacific" / "EST" → IANA
│       ├── TimeParser.kt          # regex-based detector
│       └── TimeConverter.kt       # formatting + multi-TZ rendering
└── app/
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/timetwister/app/
        │   ├── MainActivity.kt            # Compose settings host
        │   ├── ProcessTextActivity.kt     # the ACTION_PROCESS_TEXT handler
        │   ├── UserPreferences.kt         # DataStore-backed prefs
        │   └── ui/                        # Compose UI
        └── res/                           # icon + strings + theme stub
```

`core` is a plain Kotlin module with no Android dependencies, so tests run on the JVM (no emulator required) and the same logic could be lifted into a CLI or a desktop port.

## Build & run

### Dev loop

1. Install Android Studio (Giraffe or later — AGP 8.7 requires a recent IDE).
2. Open `android/` in Android Studio.
3. First sync will generate the Gradle wrapper jar and `local.properties`.
4. Plug in a device (USB debugging on) or start an emulator.
5. Run the `app` configuration. APK installs, launcher icon appears.

### Command line (no IDE)

The Gradle wrapper jar isn't committed (keeps the repo text-only). Android Studio regenerates it on first project sync; if you're going IDE-less, grab Gradle 8.10+ and run `gradle wrapper` once in `android/` to produce the jar, then:

```bash
cd android
./gradlew :app:installDebug     # build + push to connected device
./gradlew :core:test            # run core unit tests
```

No Mac required. Works on Windows + Linux + macOS.

### Distribute APK to friends

```bash
cd android
./gradlew :app:assembleDebug
# APK ends up at app/build/outputs/apk/debug/app-debug.apk
```

Send the APK over any channel (WhatsApp, Signal, email). Recipients need to allow "Install unknown apps" for whichever app they opened the APK with — Android 13+ walks them through it.

### Signed release APK

`assembleRelease` builds an unsigned APK out of the box (useful for smoke checks). To produce a signed APK suitable for Play Store upload or sideload distribution, generate a keystore once and point the build at it:

```bash
# One-time keystore generation. Use a strong password and KEEP THIS FILE SAFE —
# losing it means you can never push updates to the same app listing.
keytool -genkey -v -keystore timetwister-release.jks \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -alias timetwister
```

Then either drop a `app/keystore.properties` (gitignored) next to the module:

```properties
storeFile=/abs/path/to/timetwister-release.jks
storePassword=...
keyAlias=timetwister
keyPassword=...
```

…or set the equivalent env vars (preferred for CI):

```bash
export TIMETWISTER_KEYSTORE_PATH=/abs/path/to/timetwister-release.jks
export TIMETWISTER_KEYSTORE_PASSWORD=...
export TIMETWISTER_KEY_ALIAS=timetwister
export TIMETWISTER_KEY_PASSWORD=...

cd android && gradle :app:assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

If none of those are set, `assembleRelease` still builds — just unsigned. Play Store / Google Play Developer account is $25 one-time.

## Using it

1. Open TimeTwister once. Pick the zones you want every stamp to include (defaults to your local zone + the four US zones).
2. Go to any messaging app. Type a time like `5pm CT` (or `17:00 ET`, or `5:30pm pacific`).
3. Long-press to select the phrase.
4. Tap the overflow (⋮) in the selection toolbar. You'll see **TimeTwister**.
5. Tap it. The selection is replaced with `5pm CT (6pm ET · 3pm PT)`.
6. Send.

If the host app is read-only (you're trying this on a received message), the selection toolbar still shows TimeTwister but tapping it is a no-op — we can't write back to text we don't own.

## What's different from the iOS build

| Thing | iOS | Android |
|---|---|---|
| Primary entry point | Custom keyboard extension | `ACTION_PROCESS_TEXT` activity |
| Keyboard switches per conversion | 2 (system → TimeTwister → system) | **0** |
| Permissions needed | "Allow Full Access" on the keyboard | None |
| Distribution | TestFlight ($99/yr, 90-day rebuild) | Sideload APK or Play Store ($25 one-time) |
| Build host | macOS only (Xcode) | Any OS |

`ACTION_PROCESS_TEXT` is the reason Android is the primary platform for this project. iOS doesn't expose an equivalent, which is why the iOS build is stuck with the keyboard-extension trade-off.

## Tests

`./gradlew :core:test` covers the parser and stamp rendering. They run on the JVM, no emulator needed. The test suite mirrors the iOS `TimeConverterTests.swift` one-for-one so any divergence between platforms shows up fast.
