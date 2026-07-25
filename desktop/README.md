# TimeTwister — Desktop

Cross-platform tray app. Reuses the same Kotlin core as the Android build (`android/core/`) via a gradle path mapping — no source duplication.

## What it does

You copy a phrase containing a time reference. Click the tray icon → **Convert clipboard**. Paste — the clipboard now holds the multi-TZ-stamped version.

```
copy:   "lets do 5pm CT"
paste:  "lets do 5pm CT (6pm ET · 3pm PT)"
```

Same logic as the Android selection-toolbar flow and the iOS Action Extension, adapted to the only universal text primitive on the desktop: the clipboard.

## Build & run

No wrapper jar is committed (matches the Android module's convention). Install Gradle 8.10+ from your package manager, then:

```bash
cd desktop
gradle :app:run                  # launch the tray app
gradle :app:installDist          # produces ./app/build/install/app/bin/app(.bat)
gradle :app:distZip              # zips a shippable JRE-less bundle
```

Runs on Windows, macOS, and Linux — pure AWT/Swing, no native bits.

## Settings

Right-click the tray icon → **Settings…** to add/remove zones. Config is plain text:

```
$HOME/.timetwister/zones.txt
```

One IANA zone id per line, in render order. You can hand-edit it.

## Why no Compose Desktop / native helpers

Two reasons:
- Compose Desktop would multiply the jar size by 30× for a UI that's a list and two buttons.
- The privacy posture is "no third-party SDKs"; AWT/Swing ship with the JDK.

Future work (v0.5+):
- Global hotkey (e.g. Ctrl+Shift+T) so the convert-clipboard flow doesn't need a tray click. Needs JNI / `jkeymaster` — deferred until there's demand.
- macOS Catalyst port that reuses the iOS keyboard/extension targets — separate question, lives under `ios/`.
