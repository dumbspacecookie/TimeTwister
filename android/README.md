# TimeTwister — Android

Android build of TimeTwister. Uses `ACTION_PROCESS_TEXT` as the primary entry point — the user long-presses a time reference in any app (WhatsApp, Signal, Messages, Telegram, Discord, …), picks **TimeTwister** from the selection toolbar, and the stamp gets spliced into the selection inline.

No keyboard switching, no accessibility service, no Internet permission.

One permission *is* declared: `READ_CONTACTS`. It's used only by the optional "Suggest from contacts" button in Settings, which reads phone-number country codes to propose target zones. It's a runtime permission — nothing is read unless you tap that button and grant it, a disclosure of what's read is shown *before* the system dialog, and the conversion flow works fine if you never do.

One thing does leave the device, and it isn't this app: Android Auto Backup copies your saved zone list to your Google account by default. It's scoped to an explicit allowlist rather than the whole data directory — see the Privacy section of the [root README](../README.md).

## Module layout

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── core/                          # pure Kotlin/JVM — no Android deps
│   └── src/main/kotlin/com/timetwister/core/
│       ├── DetectedTime.kt
│       ├── CountryZone.kt         # phone country code → plausible zone
│       ├── TimeZoneAlias.kt       # "CT" / "pacific" / "EST" → IANA
│       ├── TimeParser.kt          # regex-based detector
│       └── TimeConverter.kt       # formatting + multi-TZ rendering
└── app/
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/com/timetwister/app/
        │   ├── MainActivity.kt            # Compose settings host
        │   ├── ProcessTextActivity.kt     # the ACTION_PROCESS_TEXT handler
        │   ├── ProcessTextDecision.kt     # the five outcomes — pure, unit-tested
        │   ├── UserPreferences.kt         # DataStore-backed prefs
        │   ├── ContactZoneInferencer.kt   # optional READ_CONTACTS → zone suggestions
        │   └── ui/                        # Compose UI
        └── res/                           # icon + strings + theme stub
```

`core` is a plain Kotlin module with no Android dependencies, so tests run on the JVM (no emulator required) and the same logic could be lifted into a CLI or a desktop port.

## Build & run

### Dev loop

1. Install Android Studio (Giraffe or later — AGP 8.7 requires a recent IDE).
2. Open `android/` in Android Studio.
3. First sync will generate `local.properties`.
4. Plug in a device (USB debugging on) or start an emulator.
5. Run the `app` configuration. APK installs, launcher icon appears.

### Command line (no IDE)

The Gradle wrapper is committed — jar included — so a fresh clone needs nothing but a JDK 17:

```bash
cd android
./gradlew :app:installDebug     # build + push to connected device
./gradlew :core:test            # run core unit tests
```

CI runs the same `./gradlew`, so the Gradle version in CI is the one pinned in `gradle/wrapper/gradle-wrapper.properties` — the same one you get locally.

No Mac required. Works on Windows + Linux + macOS.

### Distribute APK to friends

```bash
cd android
./gradlew :app:assembleDebug
# APK ends up at app/build/outputs/apk/debug/app-debug.apk
```

Send the APK over any channel (WhatsApp, Signal, email). Recipients need to allow "Install unknown apps" for whichever app they opened the APK with — Android 13+ walks them through it.

### Signed release APK / AAB

Note that `isMinifyEnabled = false` and no release build has ever been distributed — `assembleRelease` today is a smoke check, not a shipping configuration.

`assembleRelease` builds an unsigned APK out of the box (an empty or missing credential is treated as "not set", so this holds in CI too). To produce a signed build suitable for Play Store upload or sideload distribution, generate a keystore once and point the build at it:

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

cd android && ./gradlew :app:assembleRelease :app:bundleRelease
# APK: app/build/outputs/apk/release/app-release.apk
# AAB: app/build/outputs/bundle/release/app-release.aab   ← Play requires this, not the APK
```

If none of those are set, `assembleRelease` still builds — just unsigned. Play Store / Google Play Developer account is $25 one-time.

#### Versioning

`versionCode`/`versionName` default to `1` / `0.1.0` and are overridable as Gradle properties:

```bash
./gradlew :app:bundleRelease -Ptimetwister.versionCode=201 -Ptimetwister.versionName=0.2.1
```

`android-release.yml` derives both from the pushed tag (`v0.2.1` → name `0.2.1`, code `major*10000 + minor*100 + patch` = `201`). Play rejects a `versionCode` it has already accepted, so every release tag must produce a new one — don't hand-edit the default back into `build.gradle.kts`.

## Using it

1. Open TimeTwister once. Pick the zones you want every stamp to include. The defaults are your own zone plus US Eastern, Central and Pacific — capped so a fresh install is never already over the readable-stamp limit.
2. Go to any messaging app. Type a time like `5pm CT` (or `17:00 ET`, or `5:30pm pacific`).
3. Long-press to select the phrase.
4. Tap the overflow (⋮) in the selection toolbar. You'll see **TimeTwister**.
5. Tap it. The selection is replaced with `5pm CT (6pm ET · 3pm PT)`.
6. Send.

If the host app is read-only — you're trying this on a message you received, which is the most intuitive thing to try first — the text can't be rewritten in place. So the converted selection goes to your clipboard and the stamp is shown to you, instead of the tap appearing to do nothing.

If your text comes back unchanged, that is usually deliberate. The parser declines anything it isn't sure of rather than guessing: relative phrases (`half past 5pm`), zone abbreviations it doesn't support, a zone separated from the time by markup (`*5pm* CT`), and bare hours with no am/pm. It would rather miss a conversion than quietly send the wrong time.

## What's different from the iOS build

| Thing | iOS | Android |
|---|---|---|
| Primary entry point | Custom keyboard extension | `ACTION_PROCESS_TEXT` activity |
| Keyboard switches per conversion | 2 (system → TimeTwister → system) | **0** |
| Permissions needed | "Allow Full Access" on the keyboard | `READ_CONTACTS`, runtime-prompted, optional |
| Distribution | TestFlight ($99/yr, 90-day rebuild) | Sideload APK or Play Store ($25 one-time) |
| Build host | macOS only (Xcode) | Any OS |

`ACTION_PROCESS_TEXT` is the reason Android is the primary platform for this project. iOS doesn't expose an equivalent, which is why the iOS build is stuck with the keyboard-extension trade-off.

## Tests

```bash
./gradlew :core:test            # 63 tests — corpus, properties, red team, perf. JVM, no emulator
./gradlew :app:testDebugUnitTest # 28 tests — the ACTION_PROCESS_TEXT decision + prefs codec
```

Both run in CI on every push, alongside the desktop build and the Swift core.

`core` is graded against a **shared 276-row corpus** at `core/src/test/resources/eval/corpus.tsv`. The Swift core reads that same file rather than a copy, so a row cannot pass on Android and fail on iOS unnoticed. On top of it: 9 property invariants over 500 seeded cases each (the same seeded inputs on both cores), a red-team regression suite, and absolute performance budgets.

The corpus deliberately keeps a few `known_gap` rows, so the headline score is **not** 100% and the report's OPEN ITEMS section actually prints. A green eval that lists nothing missing is measuring its own corpus before it measures the parser.

`:app` tests are framework-free by design: the logic worth testing was pulled into `ProcessTextDecision.kt` and `UserPreferences`' companion so plain JUnit reaches it. The Toast/clipboard/intent paths need Robolectric or instrumentation and are **not** covered. Neither number says anything about the UI — see [`PLAN.md`](../PLAN.md) for what is and isn't verified.

`StampDemo` is gated on a property rather than `@Ignore`, and the build forwards it into the test JVM and turns on stdout:

```bash
./gradlew :core:test --tests "*StampDemo*" "-Dtimetwister.demo=1"
```
