// language: Java, file: Wireless.java, target: Android 16 (API 36)
// Wireless debugging, self-contained.
//
// The app carries the platform-tools `adb` binary packaged as
// lib/arm64-v8a/libadbx.so. It is named and placed that way on purpose: since
// Android 10 an app may not exec a binary from its own data directory, but it
// MAY exec one from the directory the installer unpacked its native libraries
// into. That directory is the documented escape hatch and is exactly how LADB
// itself runs adb (its libadb.so is the adb binary).
//
// PAIRING: the user types only the 6-digit code. The pairing port is discovered
// over mDNS from the "Pair with code" screen, and the host is ALWAYS localhost -
// wireless debugging runs on this device, its pairing port is bound to loopback,
// and connecting to the phone's own LAN address is hairpin routing, which
// Android does not do. So there is nothing for the user to look up or type
// beyond the code.
//
// PERSISTENCE: adb writes its key into HOME, which is files/adbhome inside app
// storage, so the pairing survives restarts. On every launch the app reconnects
// on its own; only "Reset pairing" deletes the key and forgets the pairing.
package com.adit.pulse;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Wireless {
    private static final String BIN = "libadbx.so";

    /** Deliberately not 5037: that port may be LADB's server, with LADB's key. */
    static final int SERVER_PORT = 5038;

    /** The fixed port `adb tcpip` puts adbd on, reachable over 127.0.0.1. */
    static final int LOOPBACK_PORT = 5555;

    /**
     * The sampler, as a shell script installed on the device once per connect.
     *
     * It runs entirely on the device side and prints ONE raw line per interval.
     * Parsing happens in Java. Two reasons for the split: the shell stays trivial
     * (only `grep`/`sed`/`tr`, present in every Android toybox), and the derived
     * rates - CPU %, FPS - need the PREVIOUS sample, which the app holds anyway.
     *
     * The leading "S|" is a sentinel so a partially-read line can never be parsed
     * as a sample.
     */
    private static final String SCRIPT = String.join("\n",
        // CADENCE, AND WHY IT IS 1 s.
        //
        // The loop's work itself costs ~0.45 s of device CPU (measured: dumpsys
        // window 105ms, SurfaceFlinger dump 84ms + clear 56ms, dumpsys battery
        // 54ms, cmd battery get -f 61ms, dumpsys thermalservice 63ms, GPU loop
        // 16ms). With `sleep 0.5` that made the real cycle ~1 s, so the old "0.5 s
        // interval" was never true - the comment and the recorder's MAX_SAMPLES
        // both assumed 0.5. Making the sleep 1 s states the real cadence and
        // halves the number of dump storms, which is the single biggest CPU win.
        //
        // The expensive reads are also SPLIT ACROSS TICKS (see n below): battery
        // and temperature are slow-moving values, so reading them every other tick
        // costs nothing in usefulness and removes ~180 ms of device CPU per cycle.
        "I=1",
        "timeout 1 dumpsys SurfaceFlinger --timestats -clear -enable >/dev/null 2>&1",
        "upkg=; uuid=; n=0",
        // The GPU nodes are probed ONCE. On this device all four are permission-
        // denied, so the per-tick loop was 16 ms of pure waste forever. The result
        // is cached in $gpuok and the loop is skipped when it is empty.
        "gpuok=",
        "for g in /sys/kernel/ged/hal/gpu_utilization /sys/kernel/ged/hal/gpu_loading /proc/gpufreqv2/gpu_utilization /sys/class/devfreq/*mali*/loading; do",
        "  if [ -r \"$g\" ]; then gpuok=$g; break; fi",
        "done",
        "while :; do",
        "  t=$(date +%s%N)",
        "  n=$((n+1))",
        "  cpu=$(grep -m1 '^cpu ' /proc/stat)",
        "  mt=$(grep -m1 'MemTotal' /proc/meminfo | tr -dc 0-9)",
        "  ma=$(grep -m1 'MemAvailable' /proc/meminfo | tr -dc 0-9)",
        // THE FOCUSED PACKAGE, FROM THE LAST NON-NULL mCurrentFocus LINE.
        //
        // `dumpsys window` prints mCurrentFocus more than once and the FIRST lines
        // are `null` - measured here, three lines and only the third carries a
        // window. Taking the first match (grep -m1) therefore yielded an empty
        // package, which meant no uid, which meant no layer, which meant the frame
        // rate stayed at 0 for every app. So every line is scanned and the last
        // one that actually names a window wins.
        //
        // It is the single most expensive call (~105 ms), and focus changes only
        // when the user acts, so it runs every OTHER tick and the previous value is
        // reused in between. A half-second of staleness on a focus change is
        // invisible, and the saving is ~50 ms per cycle.
        "  if [ $((n % 2)) -eq 1 ]; then",
        "    pkg=$(timeout 1 dumpsys window 2>/dev/null | grep mCurrentFocus | grep -v '=null' | tail -1 | sed -n 's/.* \\([A-Za-z0-9_.]*\\)\\/[A-Za-z0-9_.$]*.*/\\1/p')",
        "  fi",
        // FRAME RATE FROM SURFACEFLINGER, NOT gfxinfo.
        //
        // gfxinfo counts frames the app's UI THREAD drew. A game (UE4, Unity)
        // renders on its own native/GL thread, so its UI thread draws almost
        // nothing: measured on Wuthering Waves, `Total frames rendered` sat at 266
        // and never moved while the game ran, which read as 0 FPS. Any app with a
        // SurfaceView has this shape.
        //
        // SurfaceFlinger's TimeStats counts what was actually PRESENTED, per
        // layer, and reports `averageFPS` for it - the frame rate the user sees,
        // working for UI apps and games alike, and the method Google documents for
        // measuring game frame rate.
        //
        // The layer is matched by UID (the stats print `uid = N` per layer), so no
        // layer-name guessing is needed. When an app owns several layers the one
        // with the most frames is taken: that is its primary rendering surface,
        // not a status-bar or overlay layer.
        //
        // Stats are CLEARED after each dump, so averageFPS describes only the last
        // interval instead of the whole session.
        "  fc=",
        "  if [ -n \"$pkg\" ]; then",
        // The uid lookup runs only when the focused package CHANGES: the mapping
        // is stable and the lookup costs a shell round-trip.
        //
        // upkg is set only when the lookup SUCCEEDED. Caching it on a failed
        // lookup would mean that app never gets a uid for the rest of the session
        // and its FPS stays at 0 forever - the lookup has to be retried until it
        // produces a value.
        "    if [ \"$pkg\" != \"$upkg\" ]; then",
        "      uuid=$(timeout 3 cmd package list packages -U 2>/dev/null | grep -m1 \"package:$pkg uid:\" | sed \"s/.*uid://\")",
        "      if [ -n \"$uuid\" ]; then upkg=$pkg; else uuid=; fi",
        "    fi",
        "    if [ -n \"$uuid\" ]; then",
        // averageFPS FROM TIMESTATS - the real presented rate.
        //
        // TimeStats reports two things per layer: `totalFrames` (buffers the app
        // SUBMITTED) and `averageFPS` (1000 / mean present-to-present interval).
        // totalFrames is NOT the frame rate: a game stuffs buffers into the queue
        // faster than they are displayed, and measured here it read 96-100 while
        // the panel was at 60 Hz - an impossible rate. averageFPS is computed from
        // the PRESENT timestamps, so it is capped by the refresh rate and matches
        // what is on screen.
        //
        // Because the stats are CLEARED after every dump (see below), averageFPS
        // covers exactly the interval since the last sample - so it is this
        // instant's rate, not a session mean. That is what the overlay shows and
        // what the recording's 1% low / average / max are built from.
        // The awk distinguishes three cases, which is what the -- / 0 decision
        // needs:
        //   no layer for this uid   -> print nothing (app not drawing at all:
        //                              a system surface, or not on screen) -> "--"
        //   layer, but 0 frames     -> print 0 (the app is on screen and simply
        //                              presented nothing this interval) -> "0"
        //   layer with frames       -> print averageFPS of its PRIMARY layer (the
        //                              one with the most frames, since an app can
        //                              own several), clamped to the panel's own
        //                              refresh rate (AOSP quantises to whole ms,
        //                              so 16.67ms rounds to 16 and reports 62.5 on
        //                              a 60 Hz panel)
        //
        // Each layer block prints uid FIRST and averageFPS LAST (or omits it when
        // nothing was presented), so a block is COMMITTED when the next `uid` line
        // arrives - committing any earlier would read the previous block's values.
        // A layer block prints `totalFrames` TWICE - its own count, then a second
        // line inside the jank payload - and the second is often 0. Only the FIRST
        // totalFrames is the layer's real count, so it is kept once (`tfset`).
        //
        // The dump+clear pair costs ~140 ms, so BOTH halves are skipped when FPS is
        // not one of the bar's items - the clear was previously run unconditionally,
        // paying ~56 ms of device CPU for a dump that never happened. `wantfps`
        // defaults to 0 (off): the app always passes it explicitly, and a default of
        // 1 meant an accidental bare run silently turned the expensive path on.
        "      if [ \"${wantfps:-0}\" = 1 ]; then",
        "        fc=$(timeout 1 dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | awk -v u=\"$uuid\" 'function commit(){ if (uid==u) { seen=1; if (tf>best && f>=0) { best=tf; got=f } } } /^displayRefreshRate = /{ if (hz==0) hz=$3+0 } /^uid = /{ commit(); uid=$3; tf=-1; f=-1; tfset=0 } /^totalFrames = /{ if (uid==u && !tfset) { tf=$3+0; tfset=1 } } /^averageFPS = /{ if (uid==u) f=$3+0 } END{ commit(); if (!seen) exit; if (best<=0) { print 0; exit } if (hz>0 && got>hz) got=hz; print got }')",
        "        timeout 1 dumpsys SurfaceFlinger --timestats -clear >/dev/null 2>&1",
        "      fi",
        "    fi",
        "  fi",
        // BATTERY AND THERMAL, SPLIT ACROSS TICKS.
        //
        // Level and status move slowly, so they are read every other tick; the
        // current reading forces a driver read (`-f`) which is the expensive part
        // and is only needed for the watt figure, so it follows the same cadence.
        // Temperature is the heaviest single dump (~63 ms) and is read every
        // fourth tick - a quarter of the cost, with no visible difference.
        "  if [ $((n % 2)) -eq 0 ]; then",
        "    bat=$(timeout 1 dumpsys battery 2>/dev/null)",
        "    lvl=$(echo \"$bat\" | sed -n 's/.*level: \\([0-9]*\\).*/\\1/p' | head -1)",
        "    st=$(echo \"$bat\" | sed -n 's/.*status: \\([0-9]*\\).*/\\1/p' | head -1)",
        "    volt=$(echo \"$bat\" | sed -n 's/^  voltage: \\([0-9]*\\).*/\\1/p' | head -1)",
        "    cur=$(cmd battery get -f current_now 2>/dev/null | tr -dc 0-9)",
        "  fi",
        "  if [ $((n % 4)) -eq 0 ]; then",
        "    thm=$(timeout 1 dumpsys thermalservice 2>/dev/null | sed -n '/Current temperatures from HAL/,/^$/p')",
        "    ct=$(echo \"$thm\" | sed -n 's/.*mValue=\\([0-9.]*\\), mType=0.*/\\1/p' | head -1)",
        "    bt=$(echo \"$thm\" | sed -n 's/.*mValue=\\([0-9.]*\\), mType=2.*/\\1/p' | head -1)",
        // The other three sensors the home chart shows: GPU (1), skin (3) and the
        // power amplifier (5). Same block, no extra dump.
        "    gt=$(echo \"$thm\" | sed -n 's/.*mValue=\\([0-9.]*\\), mType=1.*/\\1/p' | head -1)",
        "    sk=$(echo \"$thm\" | sed -n 's/.*mValue=\\([0-9.]*\\), mType=3.*/\\1/p' | head -1)",
        "    pa=$(echo \"$thm\" | sed -n 's/.*mValue=\\([0-9.]*\\), mType=5.*/\\1/p' | head -1)",
        "  fi",
        // CPU CORE CLOCKS, every tick. Eight builtin `read`s - no process spawn, so
        // this is a handful of sysfs reads and far cheaper than any dumpsys. Emitted
        // as a comma-joined list so the whole set crosses the link as one field.
        "  fq=",
        "  for c in 0 1 2 3 4 5 6 7; do",
        "    v=0; read v < /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq 2>/dev/null",
        "    fq=\"$fq$v,\"",
        "  done",
        // EACH CORE'S REAL MAXIMUM, read once. cpuinfo_max_freq is the hardware
        // ceiling (2.2 GHz on the little cluster, 3.35 GHz on the prime core here),
        // so the chart draws every core against its own true scale instead of a
        // guessed one. It never changes, so it is emitted only on the first tick.
        "  if [ $n -eq 1 ]; then",
        "    mx=",
        "    for c in 0 1 2 3 4 5 6 7; do",
        "      v=0; read v < /sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq 2>/dev/null",
        "      mx=\"$mx$v,\"",
        "    done",
        "  fi",
        "  gpu=",
        "  [ -n \"$gpuok\" ] && gpu=$(cat \"$gpuok\" 2>/dev/null | tr -dc 0-9)",
        "  echo \"S|$t|$cpu|$mt|$ma|$pkg|$fc|$lvl|$volt|$st|$cur|$ct|$bt|$gpu|$fq|$gt|$sk|$pa|$mx\"",
        "  sleep $I",
        "done");

    private Wireless() { }

    static File libDir(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getApplicationInfo();
            if (ai.nativeLibraryDir != null && !ai.nativeLibraryDir.isEmpty()) {
                File d = new File(ai.nativeLibraryDir);
                if (d.isDirectory()) return d;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static boolean bundled(Context ctx) {
        File d = libDir(ctx);
        if (d == null) return false;
        File bin = new File(d, BIN);
        return bin.isFile() && bin.canExecute();
    }

    /**
     * The name this host shows in the wireless-debugging list.
     *
     * HOW THE NAME IS ACTUALLY BUILT, since two earlier attempts guessed wrong:
     * adb's get_user_info() (adb_auth_host.cpp) reads the LOGNAME and HOSTNAME
     * environment variables and formats them as "<LOGNAME>@<HOSTNAME>" - both the
     * "%s@%s" format and the literal "LOGNAME" are in the adb binary, and the AOSP
     * source confirms it. On Android LOGNAME is unset, so adb falls back to the
     * process uid and the device shows "u0_a398@localhost".
     *
     * So the name is set in the ENVIRONMENT of every adb process (see pb()), and
     * the adbkey.pub comment follows from it when adb generates the key. The
     * format is fixed, so the displayed name is "Pulse@android", not "Pulse" -
     * adb does not allow a bare name.
     */
    private static final String DEVICE_NAME = "Pulse";

    /**
     * Create the adb key pair with our own name, if it does not exist yet.
     *
     * Done here rather than letting adb generate it: adb's first start writes the
     * key with the uid@localhost comment, and once that file exists the name is
     * set. Generating it ourselves - same RSA 2048, same PKCS#8 private key and
     * OpenSSH-format public key adb expects - means the name is right from the
     * very first pairing.
     */
    /**
     * Remove any key that is not a complete RSA key pair.
     *
     * adb generates its own key pair on first start when none exists. That is
     * fine and is now the ONLY path - the displayed name comes from the process
     * environment (see pb()), not from anything in the key files, so there is no
     * reason to hand-write a key. What must be guarded is a key left over from an
     * earlier broken build: a truncated "ssh-rsa <name>" with no base64 blob makes
     * adb refuse to start, so such a pair is deleted and adb makes a fresh one.
     */
    static void ensureUsableKey(Context ctx) {
        File dir = new File(home(ctx), ".android");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File priv = new File(dir, "adbkey");
        File pub = new File(dir, "adbkey.pub");

        // A key pair exists and is sound: nothing to do.
        if (priv.isFile() && pub.isFile() && validKeyPair(priv, pub)) return;

        // Otherwise the pair is missing or damaged. adb only GENERATES a key when
        // its server starts and finds none - so a server already running without a
        // key would never produce one, and every connect would fail while the app
        // still believed it was paired. That was the reconnect failure: prefs said
        // paired=true, .android held no adbkey at all, and the running server had
        // been started before the key was gone.
        //
        // So the server is stopped here, which is what makes the next start-server
        // create a fresh key. The pairing is already broken in this state (there is
        // no key to authenticate with), so stopping the server costs nothing.
        killServerProcess(ctx);
        if (priv.isFile()) {
            Diag.log("adb key pair is unusable; removing so adb regenerates it");
            //noinspection ResultOfMethodCallIgnored
            priv.delete();
        }
        if (pub.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            pub.delete();
        }
    }

    /**
     * True when the pair looks like a real RSA key.
     *
     * The public half must be "<type> <blob> [comment]" with a substantial base64
     * blob, and the private half must carry the PEM header adb reads. A key that
     * fails this is what made adb regenerate its own under the default name.
     */
    /**
     * True when the pair looks like a real adb key.
     *
     * THE PUBLIC KEY IS IN MINCRYPT FORMAT, NOT OpenSSH. adb writes
     * `adbkey.pub` as a bare base64 blob with no "ssh-rsa" type field and no
     * comment - measured: two whitespace fields where the FIRST is the blob.
     * An earlier version required the first field to start with "ssh-", which is
     * never true for adb's own file, so every valid key was judged broken and
     * deleted on every launch: the key was regenerated, deleted, regenerated,
     * forever, and connecting failed every time because a server started in that
     * window had no key. The check must therefore accept EITHER shape - a bare
     * blob, or the OpenSSH "ssh-rsa <blob>" form - and simply require a long
     * base64 field plus a PEM private key.
     */
    private static boolean validKeyPair(File priv, File pub) {
        try {
            String pubText = new String(java.nio.file.Files.readAllBytes(pub.toPath()),
                    StandardCharsets.UTF_8).trim();
            if (pubText.isEmpty()) return false;
            String[] f = pubText.split("\\s+");
            // Find the longest field: in mincrypt it is field 0, in OpenSSH it is
            // field 1. A real RSA-2048 blob is several hundred base64 characters.
            int longest = 0;
            for (String s : f) longest = Math.max(longest, s.length());
            if (longest < 100) return false;

            String privText = new String(java.nio.file.Files.readAllBytes(priv.toPath()),
                    StandardCharsets.UTF_8);
            return privText.contains("BEGIN") && privText.contains("PRIVATE KEY");
        } catch (Throwable t) {
            return false;
        }
    }

    /** A private HOME for adb, so its key lands in app storage and nowhere else. */
    static File home(Context ctx) {
        File h = new File(ctx.getFilesDir(), "adbhome");
        if (!h.isDirectory()) h.mkdirs();
        return h;
    }

    static final class Result {
        final boolean ok;
        final String output;
        Result(boolean ok, String output) { this.ok = ok; this.output = output; }
    }

    // ------------------------------------------------------------------ run

    private static List<String> cmd(Context ctx) {
        File d = libDir(ctx);
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(d, BIN).getAbsolutePath());
        return cmd;
    }

    private static ProcessBuilder pb(Context ctx, List<String> cmd) {
        File d = libDir(ctx);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", d.getAbsolutePath());
        pb.environment().put("HOME", home(ctx).getAbsolutePath());
        pb.environment().put("ADB_MDNS", "0");
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        pb.environment().put("ANDROID_ADB_SERVER_PORT", String.valueOf(SERVER_PORT));

        // THE DEVICE NAME, SET WHERE ADB ACTUALLY READS IT.
        //
        // The name this host shows in the wireless-debugging list is built by
        // adb's get_user_info(), which reads the LOGNAME and HOSTNAME environment
        // variables and formats them as "<LOGNAME>@<HOSTNAME>" (the format string
        // "%s@%s" and the literal "LOGNAME" are both in the adb binary, and the
        // AOSP source confirms the order). On Android LOGNAME is unset, so adb
        // falls back to the process uid - which is why the device showed
        // "u0_a398@localhost" no matter what the key file said.
        //
        // The adbkey.pub COMMENT is NOT this name: adb regenerates the public key
        // from the private key and labels it with this user@host string. So the
        // name has to be set here, in the environment of every adb process.
        pb.environment().put("LOGNAME", DEVICE_NAME);
        pb.environment().put("USER", DEVICE_NAME);
        pb.environment().put("HOSTNAME", "android");
        return pb;
    }

    private static Result run(Context ctx, String... args) {
        return runWithStdin(ctx, null, 0, args);
    }

    /**
     * Run the bundled adb. When {@code stdin} is non-null it is written after
     * {@code delayMs} - `adb pair` reads the pairing code from its terminal, and
     * the delay lets it reach its prompt first.
     */
    private static Result runWithStdin(Context ctx, String stdin, long delayMs, String... args) {
        File d = libDir(ctx);
        if (d == null) return new Result(false, "Native library directory unavailable");
        if (!new File(d, BIN).isFile()) return new Result(false, "ADB binary not found in the APK");

        List<String> cmd = cmd(ctx);
        for (String a : args) cmd.add(a);

        Process p = null;
        try {
            p = pb(ctx, cmd).start();
            if (stdin != null) {
                if (delayMs > 0) {
                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
                }
                try {
                    OutputStream os = p.getOutputStream();
                    os.write(stdin.getBytes("UTF-8"));
                    os.flush();
                } catch (Throwable t) { Diag.log("adb stdin write failed", t); }
            }
            String out = drain(p, 25000);
            int code = p.waitFor();
            String lo = out.toLowerCase();
            boolean failed = lo.contains("failed") || lo.contains("cannot")
                    || lo.contains("unable") || lo.contains("error")
                    || lo.contains("wrong") || lo.contains("refused")
                    || lo.contains("timed out");
            Diag.log("adb " + String.join(" ", args) + " -> " + code + " :: "
                    + (out.length() > 300 ? out.substring(0, 300) : out));
            return new Result(!failed, out.isEmpty() ? ("exit " + code) : out);
        } catch (Throwable t) {
            Diag.log("adb run failed", t);
            return new Result(false, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    private static String drain(Process p, long timeoutMs) {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    sb.append(new String(buf, 0, n, "UTF-8"));
                } else if (!p.isAlive()) {
                    int n;
                    while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                    break;
                } else {
                    try { Thread.sleep(40); } catch (InterruptedException ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------- lifecycle

    /** Make sure our own server is up, and point {@link Adb} at it. */
    /**
     * Make sure our own server is up, and point {@link Adb} at it.
     *
     * Serialised, because two callers can race here - the sampler's reconnect and
     * the settings screen's status check both call this - and two `start-server`
     * runs at once race for the port and one reports "ADB server didn't ACK"
     * (seen in the server log: two server processes, pids 3294 and 3308, started
     * in the same millisecond). One start at a time, and a caller that finds the
     * server already up returns immediately.
     */
    private static final Object SERVER_LOCK = new Object();

    static Result startServer(Context ctx) {
        synchronized (SERVER_LOCK) {
            ensureUsableKey(ctx);

            // IF OUR SERVER IS ALREADY ANSWERING, USE IT. Nothing else.
            //
            // `adb start-server` ABORTS THE WHOLE PROCESS when its port is already
            // bound: the bundled binary dies on
            //   "could not install *smartsocket* listener: Address already in use"
            // with SIGABRT, and because it is a native abort it takes Pulse down
            // with it - which is the crash that killed the reaper and made the app
            // reload from scratch. So the port is probed FIRST, and `start-server`
            // is only run when nothing is listening.
            Adb.useOwnServer(SERVER_PORT);
            if (serverAlive()) {
                return new Result(true, "already running");
            }

            // PORT BOUND BUT NOT ANSWERING = a dead server's socket is still held.
            // `start-server` would abort on it (the same SIGABRT), so the holder is
            // cleared first. If the port is free, killServerProcess does nothing.
            if (portBound()) {
                Diag.log("adb port held by a dead server; clearing before start");
                killServerProcess(ctx);
                sleep(400);
            }

            Result r = run(ctx, "start-server");
            Adb.useOwnServer(SERVER_PORT);
            return r;
        }
    }

    /** True when something holds our port, even if it is not answering. */
    private static boolean portBound() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", SERVER_PORT), 400);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when a healthy adb server is answering on our port.
     *
     * A plain TCP connect plus a version round-trip. This is the guard that keeps
     * `start-server` from ever being run against a live port, which is the one
     * thing that crashes the app.
     */
    private static boolean serverAlive() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", SERVER_PORT), 400);
            byte[] req = "000Chost:version".getBytes("UTF-8");
            s.getOutputStream().write(req);
            s.getOutputStream().flush();
            byte[] buf = new byte[4];
            int n = s.getInputStream().read(buf);
            return n == 4 && new String(buf, "UTF-8").equals("OKAY");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    static boolean connected(Context ctx) {
        Result r = run(ctx, "devices");
        if (!r.ok) return false;
        for (String line : r.output.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("List")) continue;
            String[] parts = s.split("\\s+");
            if (parts.length >= 2 && "device".equals(parts[1])) return true;
        }
        return false;
    }

    /**
     * Pair using only the 6-digit code.
     *
     * The port comes from mDNS while the pairing screen is open. The host is
     * loopback - see the class comment. Because the code is single-use, the first
     * port that accepts it wins and the rest must NOT be tried.
     */
    static Result pair(Context ctx, String code) {
        if (code == null || code.trim().isEmpty())
            return new Result(false, "Pairing code is empty");
        startServer(ctx);

        List<Mdns.Found> found = Mdns.discover(ctx, Mdns.PAIRING, 4000);
        if (found.isEmpty())
            return new Result(false,
                    "Pairing port not found. Make sure the \"Pair device with "
                    + "pairing code\" screen is open, then try again.");

        StringBuilder tried = new StringBuilder();
        for (Mdns.Found f : found) {
            String target = "localhost:" + f.port;
            Result r = pairOn(ctx, target, code.trim());
            if (tried.length() > 0) tried.append("; ");
            tried.append(target).append(" -> ").append(r.output);
            if (r.ok || r.output.toLowerCase().contains("successfully paired")
                    || r.output.toLowerCase().contains("already paired")) {
                new Prefs(ctx).setPaired(true);
                Adb.reset();
                return new Result(true, tried.toString());
            }
        }
        Adb.reset();
        return new Result(false, tried.toString());
    }

    /**
     * Pair against one target, code as an ARGUMENT first.
     *
     * This adb build takes the code directly ("usage: adb pair HOST[:PORT]
     * [PAIRING CODE]"), so that is tried first. The stdin form is only a fallback:
     * it has to sleep before writing so adb reaches its prompt, and that delay is
     * what produced the "protocol fault (couldn't read status length)" seen on the
     * FIRST attempt - the pairing server had already timed the connection out by
     * the time the code arrived, so adb read EOF instead of a status. The second
     * attempt then worked because the server was freshly restarted. Passing the
     * code as an argument removes the timing window entirely.
     */
    private static Result pairOn(Context ctx, String target, String code) {
        Result r = run(ctx, "pair", target, code);
        String lo = r.output.toLowerCase();
        if (r.ok || lo.contains("successfully paired") || lo.contains("already paired")) {
            return r;
        }
        // Some builds insist on the code on stdin; only then pay the settle delay.
        Result r2 = runWithStdin(ctx, code + "\n", 900, "pair", target);
        String lo2 = r2.output.toLowerCase();
        if (r2.ok || lo2.contains("successfully paired") || lo2.contains("already paired")) {
            return r2;
        }
        return r.output.isEmpty() ? r2 : r;
    }

    /**
     * Connect to the device's wireless debugging.
     *
     * Tries every port mDNS reports: Android keeps advertising a stale instance
     * after the port changes, and the stale one is often listed first.
     */
    /**
     * Connect to the device's wireless debugging.
     *
     * THE PORT CHANGES EVERY TIME wireless debugging restarts (a force-stop of
     * this app does not, but the toggle and a reboot do), and Android keeps
     * advertising OLD instances over mDNS - measured on this device, the connect
     * service returned stale ports alongside the live one, and a stale port is
     * TCP-open long enough to be tried and rejected. So:
     *
     *   1. mDNS is asked for EVERY candidate, not just the first.
     *   2. A saved target is tried LAST, not first. It is the most likely to be
     *      dead after a restart, and trying it first only delays the live port.
     *   3. A candidate is accepted only when `adb devices` then reports a real
     *      device - `adb connect` alone can print success against a dead port.
     */
    static Result connect(Context ctx) {
        startServer(ctx);

        // LOOPBACK MODE: one fixed target, no discovery.
        //
        // `adb tcpip 5555` puts adbd on a fixed port reachable over the phone's own
        // 127.0.0.1, so there is nothing to discover - the address never changes and
        // never rotates. That is the whole point: the link survives Wi-Fi going
        // away, because loopback is internal to the device.
        if (Prefs.LOOPBACK.equals(new Prefs(ctx).connMode())) {
            Result r = run(ctx, "connect", "localhost:" + LOOPBACK_PORT);
            if (connected(ctx)) {
                Adb.reset();
                return new Result(true, r.output);
            }

            // SELF-HEAL: THE DEVICE MAY HAVE LEFT LOOPBACK WITHOUT THE APP KNOWING.
            //
            // Loopback is runtime state on the DEVICE - a reboot, or the toggle being
            // flipped elsewhere, drops it - while the app's stored mode says
            // "loopback" and keeps trying port 5555, which is now closed. That is
            // exactly the stuck state reported: "Connection refused" on 5555 forever.
            // So if the loopback port is dead, adbd is asked where it really is; if
            // it is no longer on that port, the app switches itself back to wireless
            // rather than looping on a port that will never answer.
            String tcpPort = Adb.shell("getprop service.adb.tcp.port");
            boolean onLoopbackPort = tcpPort != null
                    && tcpPort.trim().equals(String.valueOf(LOOPBACK_PORT));
            if (!onLoopbackPort) {
                Diag.log("loopback mode set but adbd is not on "
                        + LOOPBACK_PORT + " (port reads [" + tcpPort + "]); "
                        + "switching back to wireless");
                new Prefs(ctx).setConnMode(Prefs.WIRELESS);
                new Prefs(ctx).setLastTarget("");
                Adb.reset();
                // Fall through to the normal wireless discovery below.
            } else {
                Adb.reset();
                return new Result(false, r.output);
            }
        }

        // Several discovery rounds. mDNS caches, and right after wireless
        // debugging restarts it can answer with the PREVIOUS port while the new
        // one is already live - measured here: the app was handed 41131 (dead)
        // while the device was really listening on 37201. One round is therefore
        // not enough; re-asking lets the stale record expire and the live one
        // arrive.
        StringBuilder tried = new StringBuilder();
        for (int round = 0; round < 3; round++) {
            List<String> targets = new ArrayList<>();
            for (Mdns.Found f : Mdns.discover(ctx, Mdns.CONNECT, 2500)) {
                String t = "localhost:" + f.port;
                if (!targets.contains(t)) targets.add(t);
            }
            // The saved target goes LAST: after a restart it is usually stale, and
            // a stale port wastes a full connect timeout before the live one.
            String last = new Prefs(ctx).lastTarget();
            if (last != null && !last.isEmpty() && !targets.contains(last)) targets.add(last);

            for (String t : targets) {
                Result r = run(ctx, "connect", t);
                if (tried.length() > 0) tried.append("; ");
                tried.append(t).append(" -> ").append(r.ok ? "ok" : r.output);
                // Accept on a REAL device, not on adb's own report: `adb connect`
                // prints "failed to connect" for a stale port and can also claim
                // success against one that is not actually attached.
                if (connected(ctx)) {
                    new Prefs(ctx).setLastTarget(t);
                    Adb.reset();
                    return new Result(true, tried.toString());
                }
            }
            Adb.reset();   // force a fresh serial lookup on the next round
        }
        return new Result(false, tried.length() == 0
                ? "Connect port not found. Make sure wireless debugging is on."
                : tried.toString());
    }

    /**
     * Reconnect with no user action, for app start.
     *
     * Does nothing unless a pairing has succeeded before: an unpaired device has
     * nothing to reconnect to, and probing would burn seconds of every launch.
     */
    /**
     * Connect once, no matter how many callers ask at the same time.
     *
     * The home screen, the sampler's reconnect and a module's `Shell.ensure` can
     * all decide to reconnect at the same moment. Left alone each runs its own
     * mDNS discovery and `adb connect`, so the device sees a burst of connections
     * and the app spawns a thread per caller - the stacked-reconnect cost the audit
     * found. The lock makes the first caller do the work and the rest wait, then
     * see the device already connected and return at once.
     */
    private static final Object CONNECT_LOCK = new Object();

    static boolean autoConnect(Context ctx) {
        if (!bundled(ctx)) return false;
        Prefs p = new Prefs(ctx);
        if (!p.paired()) return false;
        if (connected(ctx)) return true;
        synchronized (CONNECT_LOCK) {
            // Re-check inside the lock: while this caller waited, the caller that
            // held the lock may have finished connecting.
            if (connected(ctx)) return true;
            Result r = connect(ctx);
            Diag.log("adb auto-connect: " + (r.ok ? "ok" : r.output));
            return r.ok;
        }
    }

    /**
     * Turn wireless debugging back on, if the system has it off.
     *
     * WHY THIS EXISTS. When the device reboots, the wireless-debugging toggle can
     * come back OFF - and then there is no port to find and nothing to connect to,
     * however well paired the device is. The app would sit on "Not connected" with
     * no way forward except Settings. Writing the toggle back is a `settings put`,
     * which the shell is allowed to do (no extra permission needed), so the app can
     * simply switch it on and then let the normal connect run.
     *
     * Best effort: a device that refuses the write just falls through to the normal
     * connect, which reports the truth.
     */
    static void ensureWirelessEnabled(Context ctx) {
        String out = Adb.shell("settings get global adb_wifi_enabled");
        if (out != null && out.trim().startsWith("0")) {
            Diag.log("wireless debugging was off; switching it on");
            Adb.shell("settings put global adb_wifi_enabled 1");
            // adbd needs a moment to bring the TLS server up before discovery.
            sleep(2500);
        }
    }

    /**
     * Switch to loopback: ask adbd for a fixed port, then connect over 127.0.0.1.
     *
     * `adb tcpip 5555` restarts adbd listening on that fixed port. From then on the
     * app talks to 127.0.0.1 - the phone's own internal network - so the link works
     * with Wi-Fi off, on mobile data only, or in airplane mode.
     *
     * IT ONLY WORKS WHILE ALREADY CONNECTED. The command travels over the live
     * wireless link, so a device that has never been paired cannot arm loopback at
     * all - pairing and a working wireless connection come first, and only then can
     * the app switch to loopback. It also does not survive a reboot (the port is
     * runtime state, and `persist.adb.tcp.port` needs root), so it must be re-armed
     * after each restart - which the UI says plainly.
     */
    static Result useLoopback(Context ctx) {
        if (!connected(ctx)) {
            return new Result(false, "Not connected. Pair over wireless debugging first.");
        }

        // TARGET THE WIRELESS TRANSPORT EXPLICITLY.
        //
        // When two transports are attached (wireless AND a loopback entry from an
        // earlier switch), a bare `adb tcpip` is ambiguous and fails with "more than
        // one device/emulator". Naming the wireless transport with -s removes the
        // ambiguity without disconnecting anything - an earlier version disconnected
        // every non-loopback entry first, but it failed to recognise adb's name for
        // the loopback port and disconnected THAT too, leaving no device at all:
        // "no devices/emulators found", which is the failure that was reported.
        String target = wirelessSerial(ctx);
        Result r = target != null
                ? run(ctx, "-s", target, "tcpip", String.valueOf(LOOPBACK_PORT))
                : run(ctx, "tcpip", String.valueOf(LOOPBACK_PORT));

        // CONFIRM THE SWITCH, BUT GIVE ADBD TIME TO COME BACK.
        //
        // `adb tcpip` restarts adbd, so for a moment after it the transport is gone
        // and `getprop` returns NOTHING - which read as "the switch did not take"
        // even though the command had just printed "restarting in TCP mode port:
        // 5555". That is exactly how a working switch was reported as a failure. So
        // the port is read back with retries, and the command's own success line is
        // accepted as corroboration: either the port reads 5555, or the command said
        // it restarted.
        String said = r.output == null ? "" : r.output.toLowerCase();
        boolean commandSaidOk = said.contains("restarting in tcp mode");
        String tcpPort = null;
        for (int i = 0; i < 6; i++) {
            tcpPort = Adb.shell("getprop service.adb.tcp.port");
            if (tcpPort != null && tcpPort.trim().equals(String.valueOf(LOOPBACK_PORT))) break;
            sleep(700);
        }
        boolean portOk = tcpPort != null
                && tcpPort.trim().equals(String.valueOf(LOOPBACK_PORT));
        if (!portOk && !commandSaidOk) {
            Diag.log("tcpip did not take: port reads [" + tcpPort + "], said: " + r.output);
            return new Result(false, "Could not switch to loopback mode.");
        }
        Diag.log("tcpip accepted (port reads [" + tcpPort + "], said: " + r.output + ")");

        // MARK THE MODE NOW, BEFORE CONNECTING.
        //
        // adbd is already on the loopback port at this point, so the device IS in
        // loopback mode whether or not the app manages to attach this second. The
        // mode is a statement about the DEVICE, and setting it only on a successful
        // connect meant a user who left the app mid-retry came back to a device on
        // 5555 with the app still believing it was on wireless.
        new Prefs(ctx).setConnMode(Prefs.LOOPBACK);

        // The wireless transport is now dead; drop its stale entry so `adb devices`
        // shows exactly one device and nothing is ambiguous afterwards.
        if (target != null) run(ctx, "disconnect", target);

        sleep(1500);
        Adb.reset();
        for (int i = 0; i < 6; i++) {
            run(ctx, "connect", "localhost:" + LOOPBACK_PORT);
            if (connected(ctx)) {
                Adb.reset();
                Diag.log("switched to loopback on " + LOOPBACK_PORT);
                return new Result(true, "Loopback on " + LOOPBACK_PORT);
            }
            sleep(800);
        }
        // The mode stays set: the device is on loopback, so a later connect will
        // find it. Reporting success is still honest about the attach failing.
        return new Result(false, "adbd is on " + LOOPBACK_PORT
                + " but the loopback connect did not attach. Pulse will retry.");
    }

    /**
     * True when a serial names the LOOPBACK transport specifically.
     *
     * THE PORT IS WHAT DISTINGUISHES THEM, NOT THE HOST. The app reaches wireless
     * debugging through mDNS, which resolves to `localhost:<random port>` - so a
     * wireless transport is ALSO spelled `localhost:...`. Treating any `localhost:`
     * as loopback therefore misread the wireless link as the loopback one, which
     * left `wirelessSerial()` with nothing to return, made `adb tcpip` run
     * untargeted, and made it fail with "more than one device/emulator" - the
     * intermittent switch failure. Only the loopback port counts:
     *   localhost:5555 / 127.0.0.1:5555   - connected by address
     *   emulator-5554                     - adb's name for adb port 5555
     */
    private static boolean isLoopbackSerial(String s) {
        if (s == null) return false;
        if (s.startsWith("localhost:") || s.startsWith("127.0.0.1:")) {
            int colon = s.lastIndexOf(':');
            try {
                return Integer.parseInt(s.substring(colon + 1)) == LOOPBACK_PORT;
            } catch (Throwable ignored) {
                return false;
            }
        }
        if (s.startsWith("emulator-")) {
            try {
                int consolePort = Integer.parseInt(s.substring("emulator-".length()));
                return consolePort + 1 == LOOPBACK_PORT;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /** The attached transport that is NOT loopback, or null when there is none. */
    private static String wirelessSerial(Context ctx) {
        Result r = run(ctx, "devices");
        if (r.output == null) return null;
        for (String line : r.output.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("List")) continue;
            String[] parts = t.split("\\s+");
            if (parts.length < 2 || !"device".equals(parts[1])) continue;
            if (!isLoopbackSerial(parts[0])) return parts[0];
        }
        return null;
    }

    /**
     * Switch back to wireless debugging.
     *
     * `adb usb` takes adbd out of TCP mode; the system then restarts wireless
     * debugging on its own, and normal discovery finds the new port. Run over the
     * loopback link, which is still live at this point.
     */
    static Result useWireless(Context ctx) {
        // TARGET THE LOOPBACK TRANSPORT, for the same reason the switch the other
        // way targets the wireless one: with two transports attached a bare command
        // is ambiguous and fails with "more than one device/emulator". That failure
        // was invisible here - the code went on to reconnect over mDNS and reported
        // success, while adbd stayed on the loopback port. So `usb` is aimed at the
        // loopback serial, and the result is CHECKED before believing it.
        // SEND `usb` THROUGH A VALID TRANSPORT, AND FALL BACK IF THAT ONE IS STALE.
        //
        // With two transports attached, a bare `adb usb` is ambiguous ("more than
        // one device/emulator"), so the loopback entry is named with -s. But that
        // entry can itself be stale ("error: device offline"), so if the targeted
        // call errors the untargeted form is tried.
        String target = loopbackSerial(ctx);
        Result u = null;
        if (target != null) u = run(ctx, "-s", target, "usb");
        if (u == null || isAdbError(u.output)) {
            u = run(ctx, "usb");
        }

        // DO NOT DECIDE FROM THE COMMAND'S OUTPUT - ASK THE DEVICE.
        //
        // `adb usb` prints "restarting in USB mode" when it changes mode, NOTHING
        // when adbd was already there, and "no devices/emulators found" when the
        // transport it needed was already gone - which happened here, because a
        // previous step had disconnected it. In that last case the mode had in fact
        // switched, yet the output read as an error. The authoritative answer is
        // adbd's own property, so that is what is checked.
        // CONFIRM THE SWITCH, WITH RETRIES - adbd is restarting, so the property
        // reads empty for a moment. Only a port that still equals 5555 counts as
        // "did not switch"; an empty read means adbd is simply not back yet, which
        // is not evidence of failure, so the last non-empty read decides.
        String tcpPort = null;
        boolean stillLoopback = false;
        for (int i = 0; i < 6; i++) {
            tcpPort = Adb.shell("getprop service.adb.tcp.port");
            String t = tcpPort == null ? "" : tcpPort.trim();
            if (t.equals(String.valueOf(LOOPBACK_PORT))) { stillLoopback = true; break; }
            if (t.equals("0")) { stillLoopback = false; break; }
            sleep(700);   // empty read: adbd not back yet, try again
        }
        if (stillLoopback) {
            Diag.log("adbd still on tcp port [" + tcpPort + "] after `usb`: " + u.output);
            return new Result(false, "Could not leave loopback mode.");
        }
        Diag.log("left loopback mode (port reads [" + tcpPort + "], usb said: "
                + u.output + ")");

        // THE MODE IS A STATEMENT ABOUT THE DEVICE, SO IT IS SET AS SOON AS THE
        // DEVICE HAS MOVED - not after the app manages to reconnect. Setting it
        // only on a successful reconnect is what left the app stuck showing
        // "loopback" while adbd was already back on wireless.
        Adb.reset();
        new Prefs(ctx).setConnMode(Prefs.WIRELESS);
        // adbd is restarting; clear the saved target so discovery is not led to the
        // dead loopback port, then reconnect normally.
        new Prefs(ctx).setLastTarget("");
        // Drop the now-dead loopback entry so nothing stays ambiguous.
        if (target != null) run(ctx, "disconnect", target);

        for (int i = 0; i < 5; i++) {
            Result r = connect(ctx);
            if (r.ok) {
                Diag.log("restored wireless debugging");
                return new Result(true, "Wireless debugging restored");
            }
            sleep(1000);
        }
        return new Result(false, "Left loopback mode, but could not reconnect. "
                + "Make sure wireless debugging is on.");
    }

    /** True when an adb result carries an error, as opposed to an empty success. */
    private static boolean isAdbError(String out) {
        if (out == null) return true;
        String s = out.trim().toLowerCase();
        if (s.isEmpty()) return false;   // empty is a legitimate success
        return s.startsWith("error") || s.contains("not found")
                || s.contains("failed") || s.contains("offline");
    }

    /** The attached transport that IS loopback, or null. */
    private static String loopbackSerial(Context ctx) {
        Result r = run(ctx, "devices");
        if (r.output == null) return null;
        for (String line : r.output.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("List")) continue;
            String[] parts = t.split("\\s+");
            if (parts.length < 2 || !"device".equals(parts[1])) continue;
            if (isLoopbackSerial(parts[0])) return parts[0];
        }
        return null;
    }

    /**
     * Forget the pairing completely: stop the server, delete the key, clear the
     * flag. This is the one action the brief says must break the persistent
     * pairing, so it removes the key file rather than only the flag.
     *
     * The server is killed by PID, not with `adb kill-server`. That command asks
     * the server to exit and then waits for it, but the server does not exit until
     * its last client disconnects - and this call IS a client - so it blocked
     * until the timeout, every time. A stale server is not cosmetic: it holds the
     * KEY IN MEMORY from when it started, so a pairing made against a new key
     * file would register with the old key and every later connect would fail
     * with "failed to connect". Killing the process is the only reliable way.
     */
    static void resetPairing(Context ctx) {
        killServerProcess(ctx);
        File h = home(ctx);
        deleteTree(h);
        Adb.reset();
        new Prefs(ctx).setPaired(false);
        Diag.log("pairing reset");
    }

    /**
     * Kill the adb server this app started, by the pid adb records for it.
     *
     * adb writes the server's pid to a file named for the port (`adb.5038`) in
     * HOME. Signalling that pid is immediate and does not depend on the server
     * choosing to shut down. If the file is missing the pid is also hunted by
     * process name, so a server whose pid file was deleted still dies.
     */
    private static void killServerProcess(Context ctx) {
        // 1. The pid file adb maintains for our port.
        File pidFile = new File(home(ctx), "adb." + SERVER_PORT);
        long pid = readPid(pidFile);
        if (pid > 0) {
            signalKill(pid);
            Diag.log("killed adb server pid " + pid);
        }
        // 2. The pid file may be absent (deleted with the home dir); fall back to
        //    killing by name so no stale server can survive a reset.
        killByName(ctx);
    }

    private static long readPid(File f) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8).trim();
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') d.append(c);
                else if (d.length() > 0) break;
            }
            return d.length() == 0 ? -1 : Long.parseLong(d.toString());
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void signalKill(long pid) {
        try {
            android.os.Process.sendSignal((int) pid, 9);
        } catch (Throwable t) {
            Diag.log("signal kill failed for " + pid, t);
        }
    }

    /** Kill any running adb server of ours by process name. */
    private static void killByName(Context ctx) {
        try {
            java.io.File proc = new java.io.File("/proc");
            File[] kids = proc.listFiles();
            if (kids == null) return;
            File libDir = libDir(ctx);
            if (libDir == null) return;
            String own = libDir.getAbsolutePath();
            for (File p : kids) {
                String n = p.getName();
                if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
                long pid;
                try { pid = Long.parseLong(n); } catch (Throwable t) { continue; }
                if (pid == android.os.Process.myPid()) continue;
                try {
                    String cmd = new String(java.nio.file.Files.readAllBytes(
                            new File(p, "cmdline").toPath()), StandardCharsets.UTF_8);
                    // Only OURS: the cmdline must name a binary inside THIS app's
                    // own native library directory, so another app's adb (LADB's,
                    // Shizuku's) is never touched.
                    if (cmd.contains(BIN) && cmd.contains(own)) signalKill(pid);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Diag.log("killByName failed", t);
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------------------------------------------------------------- script

    /**
     * Install the sampler as a file on the device, once per connect.
     *
     * Why a file and not an inline `sh -c`: the loop is quoted several levels deep
     * (sed scripts inside a shell inside a shell), and every extra level is
     * another chance for a backslash to be eaten. A file written once by a quoted
     * heredoc has exactly one level of quoting, and the run command is then just
     * `sh /data/local/tmp/.pulse.sh`.
     *
     * /data/local/tmp is the shell user's own writable directory - no permission
     * needed and no dependency on the app's private storage, which the shell
     * user cannot read.
     */
    static final String REMOTE_SCRIPT = "/data/local/tmp/.pulse.sh";

    static boolean installScript(Context ctx) {
        String cmd = "cat > " + REMOTE_SCRIPT + " <<'PULSE_EOF'\n"
                + SCRIPT + "\nPULSE_EOF\n"
                + "echo INSTALLED_$(wc -l < " + REMOTE_SCRIPT + ")";
        String out = Adb.shell(cmd);
        boolean ok = out != null && out.contains("INSTALLED_");
        Diag.log("install sampler: " + (ok ? "ok " + out.trim() : "FAILED " + out));
        return ok;
    }

    /**
     * The command the sampler session runs.
     *
     * `wantfps` is passed in from the app's current bar contents, so the script
     * can skip the SurfaceFlinger TimeStats dump+clear pair (~140 ms of device CPU
     * per cycle) when FPS is not one of the displayed metrics. The value is
     * exported for the script and the script is invoked in the same command.
     */
    static String samplerCommand(boolean wantFps) {
        return "wantfps=" + (wantFps ? "1" : "0") + " sh " + REMOTE_SCRIPT;
    }

    /**
     * Does this device expose a readable GPU load node?
     *
     * Asked once, for the settings screen, so it does not offer a GPU toggle on a
     * device where the node is gated - most MediaTek builds hide it from the
     * shell user, and a control that can never do anything is dead UI.
     */
    static boolean gpuReadable() {
        String out = Adb.shell(
                "for g in /sys/kernel/ged/hal/gpu_utilization /sys/kernel/ged/hal/gpu_loading "
                + "/proc/gpufreqv2/gpu_utilization /sys/class/devfreq/*mali*/loading; do "
                + "[ -r \"$g\" ] && echo READABLE && break; done");
        return out != null && out.contains("READABLE");
    }
}
