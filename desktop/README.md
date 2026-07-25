# TimeTwister — Desktop

Cross-platform tray app. Reuses the same Kotlin core as the Android build (`android/core/`) via a gradle path mapping — no source duplication.

## What it does

You copy a phrase containing a time reference, run **Convert clipboard**, and paste — the clipboard now holds the multi-TZ-stamped version.

```
copy:   "lets do 5pm CT"
paste:  "lets do 5pm CT (6pm ET · 3pm PT)"
```

Same logic as the Android selection-toolbar flow and the iOS Action Extension, adapted to the only universal text primitive on the desktop: the clipboard.

### How to run a conversion

| Where | Gesture | Works on |
| --- | --- | --- |
| Tray icon | **right-click → Convert clipboard** | Windows, macOS, Linux — **this is the documented path** |
| Settings window | **Convert clipboard** button, or `Ctrl+Shift+T` while the window has focus | all platforms |
| Tray icon | left-click | Windows and most Linux DEs only — see below |

Left-clicking the tray icon also converts, but only where the OS routes it that way. **On macOS a left-click opens the tray popup menu and never reaches the app's action listener**, and Linux behaviour varies by desktop environment. The menu item is the only gesture that behaves identically everywhere, so that is the one to rely on.

### About the `Ctrl+Shift+T` shortcut

It is **not** an OS-global hotkey. It fires only when a TimeTwister window has focus.

The JDK has no API for registering a system-wide hotkey — AWT/Swing key handling sits entirely on top of the JVM's own focus subsystem and only ever sees events the OS has already routed to a window this process owns. There is no pure-JDK equivalent of Win32 `RegisterHotKey`, macOS `RegisterEventHotKey`, or an X11 passive grab. A genuinely global hotkey needs native code via JNI/JNA, i.e. a third-party dependency, which has not been added. See **Future work** below for the options.

## Build & run

The Gradle wrapper is committed under `android/`, and the desktop build is driven through it with `-p desktop`. **You do not need Gradle installed.**

```powershell
# Windows
$env:JAVA_HOME = "<path to a JDK 17+>"
.\android\gradlew.bat -p desktop :app:run              # launch the tray app
.\android\gradlew.bat -p desktop :app:installDist      # ./desktop/app/build/install/app/bin/app.bat
.\android\gradlew.bat -p desktop build                 # compile + tests
```

```bash
# macOS / Linux
export JAVA_HOME=<path to a JDK 17+>
./android/gradlew -p desktop :app:run
./android/gradlew -p desktop :app:installDist
./android/gradlew -p desktop build
```

Run from the **repo root**, not from `desktop/` — the wrapper lives in `android/`.

Pure AWT/Swing, no native bits, so it runs on Windows, macOS and Linux.

## Packaging for people who don't have Java

```powershell
.\android\gradlew.bat -p desktop :app:jpackageAppImage
```

Produces a self-contained application directory with a **bundled JRE** at:

```
desktop/app/build/jpackage/TimeTwister/
```

On Windows that is `TimeTwister.exe` plus `app/` and `runtime/` — around 130 MB, double-clickable, no Java installation required on the target machine. Zip that directory and it is shippable as-is.

This uses `jpackage`, which ships with the JDK — no Gradle plugin, no extra dependency. It deliberately targets `--type app-image` rather than a `.msi`/`.exe` installer, because installers need the [WiX Toolset](https://wixtoolset.org/) on the build machine (and `.deb`/`.rpm` need `dpkg`/`rpm-build`). An app-image needs nothing beyond the JDK. Output is platform-specific: run the task on the OS you are shipping to.

## Settings

Right-click the tray icon → **Settings…** to add/remove zones and see a live preview of the resulting stamp. Config is plain text:

```
$HOME/.timetwister/zones.txt
```

One IANA zone id per line, in render order. You can hand-edit it.

Files written by the app carry a `# timetwister-config v1` first line. That marker is how the app distinguishes "the user deliberately configured zero extra zones" from "never configured" — without it, removing your last zone would read back as unset and the defaults would silently reappear. A hand-written file with no marker keeps the forgiving behaviour: if it yields no valid zones, the defaults are used.

## Why no Compose Desktop / native helpers

Two reasons:
- Compose Desktop would multiply the jar size by 30× for a UI that's a list and two buttons.
- The privacy posture is "no third-party SDKs"; AWT/Swing ship with the JDK.

## Future work (v0.5+)

- **True global hotkey.** Impossible in pure JDK (see above). The realistic options, with their costs:
  - [`com.github.tulskiy:jkeymaster`](https://github.com/tulskiy/jkeymaster) (~40 KB) — but it pulls in JNA + JNA-platform (~2.5 MB of native stubs), and the project has been quiet for years.
  - [`com.github.kwhat:jnativehook`](https://github.com/kwhat/jnativehook) (~1 MB, bundles per-platform native libraries) — installs a *global input hook*, which reads every keystroke system-wide. That is a low-level keylogger by construction: expect antivirus/EDR heuristics to flag it on Windows, and a mandatory Accessibility permission prompt on macOS. Hard to square with the "no third-party SDKs" privacy posture.
  - Hand-rolled JNI shims per platform — no dependency, but three pieces of native code to build, sign and maintain.

  All three break the current "one jar, runs anywhere a JVM does" property, so this is an owner decision, not a default.
- macOS Catalyst port that reuses the iOS keyboard/extension targets — separate question, lives under `ios/`.
