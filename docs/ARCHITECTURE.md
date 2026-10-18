# Architecture

How Pulse is put together: what runs where, how data reaches the screen, and why
the pieces are shaped the way they are.

---

## The shape of the problem

Android gives a third-party app very little visibility into system performance.
There is no public API for "CPU load", "the focused app's frame rate", or "the
power the device is drawing". Those numbers live behind `dumpsys`, `/proc` and
`/sys` — reachable from a shell, not from an app.

Pulse therefore ships its own adb client and talks to the device's own adbd.
That is the whole design: **the app is a shell with a user interface**.

```
┌──────────────────────────────────────────────────┐
│ Pulse process                                    │
│                                                  │
│  OverlayService (foreground, wake lock)          │
│    ├── Sampler ────────► Adb ─────┐              │
│    ├── BarView (the 1-line bar)   │              │
│    ├── MonitorPanel (long-press)  │              │
│    └── Recorder ──► Store         │              │
│                                   │              │
│  Screens (Activities) ──► Shell ──┤              │
│                                   ▼              │
│                          libadb.so (bundled)     │
└───────────────────────────────────┼──────────────┘
                                    │ adb protocol
                              ┌─────▼─────┐
                              │ adbd      │
                              │ 127.0.0.1 │
                              │ :<port>   │
                              └───────────┘
```

---

## The adb transport

`Adb.java` implements the client side of the adb smart-socket protocol: it opens
a TCP connection to adbd, performs the CNXN/AUTH handshake, opens a stream, and
returns the output.

The native side is the AOSP `adb` binary built as a shared library
(`jniLibs/lib/arm64-v8a/libadbx.so`) with its dependencies. It is invoked as a
child process, not loaded into the app's address space.

Two details matter and are easy to get wrong:

**Port choice.** The app must never use port 5037. That is the default adb server
port, and another adb on the device — a desktop adb over USB, or another
debugging tool — may already own it with a different key. Pulse starts its own
server on a different port and talks only to that.

**Serial selection.** adb identifies a transport by serial. Wireless debugging
produces `localhost:<random>`, and loopback mode produces a serial whose *port*
is the loopback port. The two look alike, so the code distinguishes them by port
number, never by hostname.

`Shell.java` is the façade the rest of the app uses: `run(cmd)` for one command,
`batched(map)` to run several in a single round trip with markers between them.
Batching matters — eight sequential round trips left a screen blank for seconds.

### Connection modes

| Mode | Transport | Requires |
|---|---|---|
| Wireless | `localhost:<port>` from wireless debugging | Wi-Fi |
| Loopback | `127.0.0.1:5555` after `adb tcpip 5555` | nothing — `lo` is always up |

Loopback is established by sending `adb tcpip` through an existing wireless
link, then connecting to the loopback address. The app verifies the result with
`getprop service.adb.tcp.port` rather than trusting the command's exit status.

---

## The sampler

`Sampler.java` runs a shell loop on the device and reads one line per interval.
The loop is written as a single script (`Wireless.SCRIPT`) pushed to
`/data/local/tmp` and executed there — one process, one stream, rather than a
new `dumpsys` invocation per metric per tick.

Each line carries: cumulative CPU jiffies, memory totals, the focused package,
SurfaceFlinger's frame rate for that app's layer, battery level/voltage/status/
current, the thermal zone temperatures, per-core clocks, and GPU load where
readable.

Derived values are computed on the phone side of the stream, not per UI frame:

- **CPU %** from the delta in jiffies between consecutive samples.
- **Watts** from voltage × current, with the sign taken from the charge status
  rather than the raw current sign (devices disagree about that convention).
- **Frame rate** from SurfaceFlinger's per-layer `averageFPS`, matched to the
  focused package by uid.

The first sample of a session is discarded: it has no predecessor to difference
against, so its CPU figure is zero and publishing it makes the UI flash `0%`.

### Why one line, one interval

An earlier version invoked several `dumpsys` commands per sample. Each is a
process spawn and a full system dump; the cost showed up as CPU used by the
monitor itself. The single script amortises the work, and the sampler's own cost
is now small enough to ignore.

---

## The overlay

`OverlayService` is a foreground service holding a partial wake lock, so the
sampler keeps running when the screen goes off.

It owns two windows:

- **`BarView`** — the one-line bar. Drawn entirely in `onDraw`: the rounded
  translucent background, the app icon, and each metric. Position resets to top
  centre on every appearance (not persisted — this is deliberate).
- **`MonitorPanel`** — the long-press panel, a modal card over a dimmed backdrop,
  built from the same views as the rest of the app.

Tapping the bar toggles recording. Dragging moves it.

---

## Recording

`Recorder` decides whether a sample belongs to the current capture: it is kept
only while the focused package equals the capture's package. A departure is
counted as a pause, and the time is excluded from the active duration.

`Recording` holds the samples plus the summary statistics computed once on save:
mean, min, max, standard deviation, and the 1% / 0.1% low (the mean of the worst
1% / 0.1% of frame-rate samples — the conventional definition, not the single
worst frame).

`Store` serialises a recording to JSON in the app's files directory. Values are
written through a helper that maps `NaN` to JSON `null`; `put(double)` throws on
`NaN`, and a metric the device cannot supply is `NaN` on every sample, so a
direct put aborted the whole write and saved an empty capture.

The chart's X axis ends at the **span the samples cover**, not the wall-clock
duration: the latter includes the tail after the last sample and every paused
stretch, which would label the axis with a maximum the data never reached.

---

## Charts

`ChartView` draws both forms:

- **single** — one metric against its own value axis.
- **pair** — two metrics, the first against the left axis and the second against
  the right, each axis named at the top in the colour of its line.

Two series is a hard limit. A third would need a third scale, and a plot with
three scales cannot be read.

Layout is measured from text, not from the axis line:

- the **left and right gutters** are the width of the widest tick label on that
  axis, so the numbers sit outside the plot and can never land on a curve;
- the **bottom band** is measured from the font metrics so the time label sits
  wholly below the plot;
- the **curves are clipped** to the plot rect, so nothing can spill past an axis.

Values are drawn with a monotone-clamped cubic: the control points of each
segment are pinned inside that segment's own y-range, which removes the overshoot
a plain Catmull-Rom spline produces on a sharp change — a peak the data never
contained.

`HistView` is the distribution chart, used for frame rate and frame time. It
follows the same gutter rule and shows its values only while touched.

---

## Screens

Every screen extends `Screen`, which owns the shared visual vocabulary: the
scaffold with insets applied, `card()`, `divider()`, `text()`, `stat()`,
`button()`, `pill()`, the confirm dialog and the bottom sheet.

Nothing here is a platform widget. Cards, buttons, switches, dialogs and icons
are plain views with drawn backgrounds, so the app has one design language rather
than the system's mixed in.

Screens that read the device refresh on a timer (`autoRefresh`) rather than once
on open: a read that lands before the link is up would otherwise leave the screen
on `--` until the user left and came back.

All device I/O happens on worker threads. `Shell.run` opens a socket, and a
socket on the main thread throws `NetworkOnMainThreadException` — the shared
helpers (`openDeviceSettings`) move the work off the UI thread once, rather than
at each call site.

---

## Freeze

`FreezeService` is the reaper behind the freeze switch. Every five seconds it
force-stops each frozen package that is running again, guarded by `pidof` so it
issues no command for an app that is already dead.

`am force-stop` rather than `am kill`: `kill` only affects background processes
and a foreground app survives it, which made the switch look broken.

The hold is **not persisted as system state**. Force-stopping Pulse kills the
service, the reaper stops, and every frozen app recovers on its own. That is the
contract: a freeze lasts until it is released or Pulse is stopped. Persistent
variants (suspend, disable) exist as separate actions and are recorded so the app
can offer to release them on next launch.

---

## What the platform will not allow

These are hard limits, not bugs:

- A third-party overlay window sits below the status bar layer. It cannot be
  drawn over the status bar or the notch cutout, only inside the safe area.
- Some OEM skins hide third-party overlays while a system dialog or Settings has
  focus.
- GPU load nodes are readable on some SoCs and denied to the shell user on
  others.
- CPU frequency and governor writes require root. Reading works.
- `persist.adb.tcp.port` requires root, so the loopback port does not survive a
  reboot.
- `adb tcpip` makes adbd listen on every interface, not just loopback. This is
  adbd's behaviour (AOSP; Google IssueTracker 526109803).
