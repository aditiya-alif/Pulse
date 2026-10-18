// language: Java, file: OverlayService.java, target: Android 16 (API 36)
// Foreground service that owns the overlay bar and the sampler.
//
// FOREGROUND, not a plain started service, and that is load-bearing for the
// brief: the user wants monitoring to keep working through a screen-off press.
// A background process is frozen with the CPU when the screen goes off, so its
// 0.5 s timer stops and the history has a hole exactly where the user looked
// away. A foreground service keeps the process alive, and - with the user's
// consent on the keep-awake switch - a partial wake lock keeps the timer
// running through doze.
//
// The bar is a TYPE_APPLICATION_OVERLAY window, draggable, tap-to-open. Tapping
// it anywhere opens the monitor panel (the brief's "monitoring langsung").
package com.adit.pulse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

public class OverlayService extends Service implements Sampler.Listener {
    static final String ACTION_SHOW = "com.adit.pulse.SHOW";
    static final String ACTION_HIDE = "com.adit.pulse.HIDE";
    static final String ACTION_TOGGLE = "com.adit.pulse.TOGGLE";
    /**
     * Ask the sampler to try the link again, now.
     *
     * The sampler gives up after a few failed reconnects instead of looping
     * forever (a link that will not come back is usually being fought over by
     * another adb tool, and retrying into that keeps the other side flapping). This
     * is the deliberate "try again" - sent by the Reconnect button.
     */
    static final String ACTION_RECONNECT = "com.adit.pulse.RECONNECT";

    private WindowManager wm;
    private BarView bar;
    private WindowManager.LayoutParams lp;
    private Sampler sampler;
    private MonitorPanel panel;
    private Recorder recorder;
    private Store store;

    private PowerManager.WakeLock wake;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    /** The focused package whose icon is currently on the bar, so the icon is
     *  only re-resolved when focus actually moves. */
    private String lastPkg = "";

    /**
     * The authority on whether the overlay is up.
     *
     * A shared-preference flag cannot answer this: it records what was REQUESTED,
     * so an overlay that failed to attach - the addView threw, or the system
     * killed the service - leaves the flag saying "on" with nothing on screen.
     * That is the exact mismatch reported, so the running state lives here in the
     * service and the settings screen reads it back.
     */
    static volatile boolean RUNNING = false;
    private int savedRotation = -1;
    /**
     * True while the bar sits at its default top-centre spot.
     *
     * Pinned means "follow the default": it re-centres and re-tops on every
     * rotation. A drag clears it, and from then on the bar stays where the user
     * put it - the brief's "fixed again until moved manually" the other way round.
     * It is per-service-run, so a fresh appearance always starts pinned.
     */
    private boolean pinned = true;

    /**
     * Re-asserts the overlay window, so MIUI cannot leave it hidden.
     *
     * MIUI HIDES THIRD-PARTY OVERLAYS while a Settings-class window is in front.
     * Measured on this device with SurfaceFlinger: the overlay's layer flag reads
     * `flags:4096` normally and `flags:4097` the moment Settings opens - the extra
     * bit is "this layer is not composited". The app keeps running and its window
     * still reports isVisible=true, but nothing is drawn, which is exactly the
     * reported symptom (the bar vanishes on the media-projection dialog and on a
     * Settings sub-page, and stays gone).
     *
     * WHY IT IS CONDITIONAL AND SLOW. A relayout is a full SurfaceFlinger
     * transaction, and the bar already invalidates itself on every sample - so a
     * periodic relayout is pure waste on a device that is NOT hiding the layer.
     * The window reports whether it is shown; when it says the bar IS visible there
     * is nothing to fix and the tick does nothing. Only when the system claims the
     * bar is not visible is the layout touched, which is the case the watchdog
     * exists for. Three seconds is well inside the window where the user would
     * notice, and on a healthy device the cost is now zero.
     */
    private final Runnable reassert = new Runnable() {
        @Override public void run() {
            if (bar != null && lp != null) {
                boolean visible = bar.isShown();
                if (!visible) {
                    try { wm.updateViewLayout(bar, lp); } catch (Throwable ignored) { }
                }
            }
            main.postDelayed(this, REASSERT_MS);
        }
    };

    /** How often the window is re-asserted. See the reassert field. */
    private static final long REASSERT_MS = 3000;

    @Override public void onCreate() {
        super.onCreate();
        Diag.init(this);
        prefs = new Prefs(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sampler = new Sampler(this);
        sampler.addListener(this);
        store = new Store(this);
        recorder = new Recorder();
        recorder.setListener((rec, paused, pkg, activeMs) -> {
            if (bar == null) return;
            // The listener fires on a state CHANGE - start, pause, resume, stop -
            // not on every sample, so this only touches the bar when something
            // actually changed. The per-sample timer update is in onSample.
            bar.setRecording(rec, activeMs, paused);
        });
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            if (RUNNING) { stopSelf(); return START_NOT_STICKY; }
        }

        // A foreground service must call startForeground quickly or the system
        // kills it with a RemoteServiceException.
        startForeground(NOTIF_ID, notification());

        // A deliberate retry: clear the failure count and wake the loop. Handled
        // after startForeground so the service is valid even on a cold start.
        if (ACTION_RECONNECT.equals(action)) {
            if (sampler != null) sampler.retry();
            return START_STICKY;
        }

        if (bar == null) showBar();
        prefs.setOverlayOn(true);
        sampler.start();
        acquireWake();
        // Re-assert the window periodically, so MIUI leaving it hidden (see the
        // reassert field) cannot make the bar disappear for good.
        main.removeCallbacks(reassert);
        main.postDelayed(reassert, REASSERT_MS);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        // A capture still running when the overlay dies is saved rather than
        // lost - the user asked for the data, and killing the bar is not a
        // statement that they did not want it.
        if (recorder != null && recorder.isActive()) {
            Recording r = recorder.stop();
            if (r != null) store.save(r);
        }
        prefs.setOverlayOn(false);
        RUNNING = false;
        main.removeCallbacks(reassert);
        if (panel != null) { panel.dismiss(); panel = null; }
        if (bar != null) {
            try { wm.removeView(bar); } catch (Throwable ignored) { }
            bar = null;
        }
        if (sampler != null) { sampler.removeListener(this); sampler.stop(); }
        releaseWake();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // --------------------------------------------------------------- overlay

    private void showBar() {
        bar = new BarView(this);
        bar.setItems(new ArrayList<>(prefs.barItems()));
        applyMaxWidth();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;


        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // CENTER_HORIZONTAL with x as an offset from the middle: x=0 is centred
        // in BOTH orientations, because the platform re-centres on rotation and a
        // fixed left edge could not.
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;

        // LET THE WINDOW INTO THE NOTCH AREA.
        //
        // Without this the window is laid out BELOW the status bar / cutout, so
        // its own y=0 is the bottom of the notch (measured: parent frame started
        // at y=120) - which is why the bar could never reach the physical top and
        // why dragging upward appeared to "bounce off" the notch. ALWAYS lets the
        // window span the whole display, so y=0 is the true top of the screen and
        // the bar can sit in the notch strip. This is the same mode SystemUI's own
        // overlay uses to get a full-display frame.
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        savedRotation = rotation();
        // ALWAYS start pinned at top centre. A saved position is deliberately NOT
        // restored: the request is that every appearance resets the previous
        // placement, and restoring the last dragged spot is exactly why it kept
        // coming back somewhere other than the top.
        pinned = true;
        lp.x = 0;
        lp.y = topMargin();

        bar.setOnTouchListener(new DragTap(lp));

        try {
            wm.addView(bar, lp);
        } catch (Throwable t) {
            Diag.log("add overlay failed", t);
            stopSelf();
            return;
        }
        RUNNING = true;
        bar.setAlpha(0f);
        bar.setScaleX(0.9f);
        bar.setScaleY(0.9f);
        bar.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(300).setInterpolator(Anim.SETTLE).start();
    }

    /** The bar may not exceed the screen minus a small margin; past that it wraps. */
    private void applyMaxWidth() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int margin = (int) (24 * getResources().getDisplayMetrics().density);
        if (bar != null) bar.setMaxWidth(Math.max(120, screenW - margin));
    }

    private int rotation() {
        try {
            return ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The gap between the top of the SCREEN and the top of the bar.
     *
     * Zero: the bar is meant to sit flush against the top edge, inside the notch
     * strip. This is only meaningful now that the window declares
     * LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS - without that the window's own y=0 is
     * the bottom of the notch, and any value here is measured from the wrong
     * origin.
     */
    private int topMargin() {
        return 0;
    }

    /**
     * On rotation, a PINNED bar re-centres and re-tops itself; a bar the user has
     * moved is left where it is, except that its x is clamped so a landscape-wide
     * offset cannot push it off the now-narrow screen.
     *
     * The width budget is re-measured either way: the wrap point depends on how
     * much horizontal room there is, which just changed.
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (bar == null || lp == null) return;
        int now = rotation();
        if (now == savedRotation) return;
        savedRotation = now;
        applyMaxWidth();
        if (pinned) {
            lp.x = 0;
            lp.y = topMargin();
        } else {
            clampIntoView(lp);
        }
        try { wm.updateViewLayout(bar, lp); } catch (Throwable ignored) { }
    }

    /**
     * Drag to move; tap to start/stop a recording; long-press for the live view.
     *
     * A long press is required for the live view so a plain tap can own the
     * record toggle, which is the action the user reaches for most.
     */
    private final class DragTap implements View.OnTouchListener {
        private final WindowManager.LayoutParams p;
        private int startX, startY;
        private float downRawX, downRawY;
        private boolean moved;
        private final Runnable longPress = () -> {
            if (!moved) {
                openPanel();
                longPressed = true;
            }
        };
        private boolean longPressed;

        DragTap(WindowManager.LayoutParams p) { this.p = p; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = p.x;
                    startY = p.y;
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    moved = false;
                    longPressed = false;
                    main.postDelayed(longPress, 420);
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downRawX);
                    int dy = (int) (e.getRawY() - downRawY);
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                        if (!moved) {
                            // The user has taken control of where the bar sits:
                            // from here it no longer follows the default, so a
                            // rotation will not snap it back to the top.
                            pinned = false;
                        }
                        moved = true;
                        main.removeCallbacks(longPress);
                    }
                    p.x = startX + dx;
                    p.y = startY + dy;
                    try { wm.updateViewLayout(bar, p); } catch (Throwable ignored) { }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(Anim.SETTLE).start();
                    if (moved) {
                        clampIntoView(p);
                    } else if (!longPressed) {
                        toggleRecording();
                    }
                    return true;
            }
            return false;
        }
    }

    /** Tap handler: start a capture of the focused app, or stop the running one. */
    private void toggleRecording() {
        if (recorder.isActive()) {
            Recording r = recorder.stop();
            Recording saved = store.save(r);
            bar.setRecording(false, 0, false);
            if (saved == null) {
                toast("Nothing to save");
            } else {
                toast("Saved · " + AppIcon.get(this, saved.pkg).label);
            }
            return;
        }
        // Start: capture whatever is focused right now. The sampler reports the
        // focused package on every sample; the newest one is the current app.
        String pkg = focusedPackage();
        if (pkg == null || pkg.isEmpty()) {
            toast("No focused app to record");
            return;
        }
        if (pkg.equals(getPackageName())) {
            toast("Open another app first, then tap to record");
            return;
        }
        recorder.start(pkg);
        bar.setRecording(true, 0, false);
        toast("Recording · " + AppIcon.get(this, pkg).label);
    }

    /** The most recent focused package the sampler saw. */
    private String focusedPackage() {
        java.util.List<Sample> h = sampler.snapshot();
        for (int i = h.size() - 1; i >= 0; i--) {
            String p = h.get(i).pkg;
            if (p != null && !p.isEmpty()) return p;
        }
        return null;
    }

    private void toast(String s) {
        main.post(() -> android.widget.Toast
                .makeText(this, s, android.widget.Toast.LENGTH_SHORT).show());
    }

    private void clampIntoView(WindowManager.LayoutParams p) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int w = bar.getWidth(), h = bar.getHeight();
        // x is an offset from the centre (CENTER_HORIZONTAL), so the bar's centre
        // sits at sw/2 + x; keeping that centre on screen means x in
        // [-sw/2 + w/2, sw/2 - w/2]. A bar wider than the screen collapses the
        // range to a single centred value rather than inverting it.
        int maxOff = Math.max(0, (sw - w) / 2);
        p.x = Math.max(-maxOff, Math.min(maxOff, p.x));
        p.y = Math.max(0, Math.min(Math.max(0, sh - h), p.y));
        try { wm.updateViewLayout(bar, p); } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- panel

    private void openPanel() {
        if (panel != null) return;
        panel = new MonitorPanel(this, sampler, new MonitorPanel.Control() {
            @Override public boolean recording() { return recorder.isActive(); }
            @Override public boolean paused() { return recorder.isPaused(); }
            @Override public long activeMs() { return recorder.liveElapsedMs(); }
            @Override public void toggle() { toggleRecording(); }
        }, () -> panel = null);
        panel.show();
    }

    // ------------------------------------------------------------- listener

    @Override public void onSample(Sample s, List<Sample> history) {
        // Publish for any open screen (home shows the live readout). Cheap: a
        // volatile write, and a main-thread post only when someone is listening.
        Live.push(s);
        if (bar == null) return;

        // Recording first: it decides whether this sample counts at all.
        if (recorder.isActive()) {
            boolean kept = recorder.onSample(s);
            bar.setRecording(true, recorder.liveElapsedMs(), recorder.isPaused());
            if (panel != null) panel.onRecording(recorder, kept);
        } else if (!s.pkg.isEmpty() && !s.pkg.equals(lastPkg)) {
            // Only on an actual focus change. Resolving the icon touches the
            // PackageManager and setting it can relayout the window; doing that
            // on every sample was part of the interval-synced stutter.
            lastPkg = s.pkg;
            AppIcon.Info info = AppIcon.get(this, s.pkg);
            bar.setApp(info.icon, info.label);
        }

        bar.setSample(s);
        if (panel != null) panel.onSample(s, history);
    }

    @Override public void onLink(boolean up, String message) {
        if (panel != null) panel.onLink(up, message);
    }

    /**
     * History is wanted only while the monitor panel is open.
     *
     * The bar draws the newest sample alone, so the per-sample history copy is
     * skipped whenever the panel is closed - which is the normal state.
     */
    @Override public boolean wantsHistory() { return panel != null; }

    // ------------------------------------------------------------ wake lock

    /**
     * A partial wake lock, only while the overlay runs and only if the user left
     * the switch on. PARTIAL keeps the CPU alive without lighting the screen;
     * a screen-off press then does not suspend the sampler.
     */
    private void acquireWake() {
        if (!prefs.keepAwake()) return;
        if (wake != null && wake.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulse:sampler");
        wake.setReferenceCounted(false);
        try { wake.acquire(); } catch (Throwable t) { Diag.log("wake acquire failed", t); }
    }

    private void releaseWake() {
        if (wake != null && wake.isHeld()) {
            try { wake.release(); } catch (Throwable ignored) { }
        }
        wake = null;
    }

    // ------------------------------------------------------------ foreground

    private static final String CHANNEL = "pulse";
    private static final int NOTIF_ID = 41;

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Pulse",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /**
     * The persistent notification a foreground service requires.
     *
     * IMPORTANCE_MIN and no badge: it is a requirement, not an alert. Tapping it
     * opens the app; the action toggles the overlay off.
     */
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent hide = new Intent(this, OverlayService.class).setAction(ACTION_HIDE);
        PendingIntent phi = PendingIntent.getService(this, 1, hide,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Pulse active")
                .setContentText("Monitoring device")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Turn off", phi).build())
                .setOngoing(true)
                .build();
    }
}
