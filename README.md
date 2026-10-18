# Pulse

A performance monitor and device toolbox for Android, built on the device's own
wireless-debugging shell.

Pulse shows a live one-line overlay of CPU load, memory, temperature, frame rate
and power draw; records a session so the numbers can be read back afterwards; and
exposes the parts of the system that are normally only reachable from a shell —
process control, battery and wake-lock inspection, storage, sensors, and a
freeze switch that keeps an app stopped until you release it.

No root. No desktop. No companion app.

---

## What it does

**Live overlay** — a single dark bar at the top edge, showing the focused app's
icon and its numbers: CPU %, memory used, CPU and battery temperature, frame
rate, and wattage. The bar wraps to a second row when the screen is narrow, and
can be dragged out of the way.

**Recording** — tap the bar to start capturing the app in front of you; tap again
to save. Samples taken while a different app is focused are dropped and counted
as a pause, so a game capture is not polluted by the seconds spent in a
notification shade. Each recording opens to three sections:

- **Summary** — 1% and 0.1% low frame rate, average, peak, power and thermal
  averages, battery drain.
- **Curves** — a pair plot you build yourself: two metrics, one read against the
  left axis and one against the right, each axis named in the colour of its line.
  Below it, a single metric against its own real axis.
- **Analysis** — frame-rate and frame-time distributions, and plain-language
  notes derived from the samples.

**Toolbox** — seven modules, each its own screen:

| Module | What it covers |
|---|---|
| Apps | freeze, suspend, disable, force-stop, uninstall, clear data, app ops, background restrictions |
| Battery | per-app drain, wake locks, alarms, doze, deep sleep, battery health, charge monitoring |
| Processes | live process list with CPU and RSS, thread view, kill |
| Commands | switches for Wi-Fi, mobile data, Bluetooth and airplane mode, plus maintenance actions and your own saved commands |
| Diagnostics | logcat with filtering, bug reports |
| System | device info, storage, largest app data folders, sensor list, recording export (CSV / PNG) |
| Recordings | the capture history |

**Freeze** — pick a set of apps once, then one button stops them and keeps them
stopped. A reaper runs every five seconds while the hold is active. The hold is
deliberately self-limiting: it is not persisted as system state, so force-stopping
Pulse releases everything. There is always a release button.

---

## How it talks to the device

Pulse bundles the AOSP `adb` client and speaks the adb protocol directly to the
device's own adbd over the loopback interface. That is why the app needs
wireless debugging enabled and paired — it is using the same channel a desktop
`adb` would, from inside the phone.

Two connection modes:

- **Wireless** — connects to the port wireless debugging is listening on.
  Requires Wi-Fi.
- **Loopback** — asks adbd to also listen on a fixed port and connects through
  `127.0.0.1`. Works with Wi-Fi off, on mobile data, or in airplane mode.

Pairing uses the six-digit code from Developer options. The pairing key is kept
in the app's own files directory and survives restarts; the pairing itself
survives until wireless debugging is reset.

> **Note on loopback:** `adb tcpip` makes adbd listen on every network interface,
> not just loopback. The port is reachable from the local network while the mode
> is active. This is adbd's own behaviour, documented in AOSP and tracked as
> Google IssueTracker 526109803; the app states it in the UI before switching.

---

## Requirements

- Android 8.0 (API 26) or newer.
- Developer options with **Wireless debugging** enabled.
- The phone and, for the initial pairing, a network both are on.
- `SYSTEM_ALERT_WINDOW` for the overlay (granted through the system prompt).

No root. A handful of readings are unavailable without it — CPU frequency
control, GPU load on some SoCs, and a permanent loopback port — and the app says
so rather than failing silently.

---

## Building

The build is the Android toolchain directly — no Gradle, no daemon, no IDE.
This is what lets the project build from a terminal on the device itself.

```
./build_apk.sh              # builds into ./app/build
```

The script runs `aapt2 compile` → `aapt2 link` → `javac` → `d8` → repack →
`zipalign` → `apksigner`.

**Tools needed on `PATH`:** `aapt2`, `d8`, `apksigner`, `javac`, `python3`.

**Environment:**

| Variable | Default | Meaning |
|---|---|---|
| `ANDROID_JAR` | `$ANDROID_HOME/platforms/android.jar` | the platform jar |
| `KEYSTORE` | `./debug.keystore` | signing keystore |
| `AAPT2`, `D8`, `APKSIGNER`, `JAVAC` | same names on `PATH` | tool overrides |

The output APK is `app/build/app-debug.apk`.

The keystore is not committed. Create one, or point `KEYSTORE` at an existing
one:

```
keytool -genkeypair -v -keystore debug.keystore -storepass android \
  -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

Two small Python helpers do the packaging work: `repack.py` assembles the APK
deterministically (the Android `zip` utility's handling of an absolute archive
path varies between builds), and `zipalign.py` aligns uncompressed entries the
way the SDK tool does.

To install:

```
adb install -r -d app/build/app-debug.apk
```

If a build is already running on the device, force-stop it first — `install -r`
replaces the APK but does not restart a running process, so the old code keeps
executing and the test is misleading:

```
adb shell am force-stop com.adit.pulse
```

---

## Project layout

```
app/
  AndroidManifest.xml
  res/                     colours, theme, launcher icon
  src/com/adit/pulse/      all Java sources, one package
  jniLibs/lib/arm64-v8a/   the bundled adb client and its dependencies
build_apk.sh               the whole build
repack.py                  APK assembly
zipalign.py                entry alignment
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit
together: the sampler, the adb transport, the overlay service, and the recording
format.

---

## Limitations

- **Overlay above the status bar is not possible** for a third-party app: the
  overlay window type sits below the status bar layer. The bar is placed inside
  the notch area, which is as high as the platform allows.
- **Some OEM skins hide third-party overlays** while a system dialog or Settings
  is in front. The bar returns when that closes.
- **GPU load is unreadable on some SoCs** — the driver exposes no node to the
  shell user. The GPU series disappears rather than showing a flat zero.
- **CPU frequency and governor writes need root.** Reading the current frequency
  works; changing it does not.
- **The loopback port is not permanent.** Making adbd bind a fixed port across
  reboots requires root.

---

## Licence

No licence file is included yet. Add one before distributing.
