// language: Java, file: FreezeService.java, target: Android 16 (API 36)
// The reaper behind the reversible freeze.
//
// WHY THIS IS A SERVICE AND NOT A TIMER IN AN ACTIVITY: the point of a freeze is
// that it holds while the user is somewhere else - in a game, on the home screen -
// with Pulse's UI closed. A foreground service is the only thing Android keeps
// running in that state.
//
// WHY THAT IS ALSO THE SAFETY: this service is the ONLY thing that re-kills a
// frozen app. Force-stopping Pulse destroys this service, so the reaping stops and
// every frozen app recovers by itself. Nothing about the freeze is written into
// the system, so there is nothing left to get stuck. That is the property the
// feature is built around, and it is why the reversible mode is the default.
//
// It stops itself when the frozen list empties, so it never runs for nothing.
package com.aditiyaalif.pulse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

public class FreezeService extends Service {
    static final String ACTION_START = "com.aditiyaalif.pulse.FREEZE_START";
    static final String ACTION_STOP = "com.aditiyaalif.pulse.FREEZE_STOP";

    private static final String CHANNEL = "pulse-freeze";
    private static final int NOTIF_ID = 42;

    /**
     * How often the reaper checks.
     *
     * 5 s, chosen for how quickly a frozen app is put back down after the user
     * opens it - the shorter the gap, the closer "frozen" is to the truth. The cost
     * is bounded by the reap itself: it only force-stops apps that are RUNNING
     * (a `pidof` guard on the device), so an idle pass is one ~0.4 s round trip and
     * no force-stop work. At 5 s that is about 10% of one core while a freeze is
     * active, and nothing at all when it is not.
     */
    private static final long REAP_MS = 5000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;

    /**
     * Reap, then reschedule - and stop when there is nothing left to hold.
     *
     * The reap runs OFF the main thread: it is an adb shell command, and running it
     * on the main thread would block the service's own handler. The link is made
     * sure first, because a reaper with no shell is a reaper that does nothing
     * while telling the user the app is frozen.
     */
    private final Runnable reap = new Runnable() {
        @Override public void run() {
            if (!Freeze.anythingHeld(FreezeService.this)) {
                stopSelf();
                return;
            }
            // The auto-release backstop: a hold the user set a window for is let
            // go the moment that window passes, so nothing stays frozen by accident.
            if (Freeze.autoReleaseDue(FreezeService.this)) {
                new Thread(() -> {
                    Freeze.releaseAll(FreezeService.this);
                    main.post(FreezeService.this::stopSelf);
                }, "pulse-autorelease").start();
                return;
            }
            new Thread(() -> {
                // Without a link the reap silently does nothing. Reconnect, then
                // force-stop. Never let a dead transport look like a working freeze.
                Shell.ensure(FreezeService.this);
                Freeze.reap(FreezeService.this);
            }, "pulse-reap").start();
            main.postDelayed(this, REAP_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        Diag.init(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, notification());
        acquireWake();
        main.removeCallbacks(reap);
        main.postDelayed(reap, REAP_MS);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        main.removeCallbacks(reap);
        releaseWake();
        super.onDestroy();
    }

    /**
     * Hold a partial wake lock while the reaper runs.
     *
     * WITHOUT THIS THE FEATURE QUIETLY FAILS UNDER DOZE. The reap is a plain
     * `postDelayed`, and a dozing device defers those indefinitely - so a frozen
     * app the user reopened could sit running for minutes before the next pass,
     * which is the opposite of what "frozen" promises. The lock is scoped to the
     * service: acquired in onStartCommand, released in onDestroy, so it lives
     * exactly as long as the reaper does and no longer.
     */
    private void acquireWake() {
        if (wake != null && wake.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulse:freeze");
        wake.setReferenceCounted(false);
        try { wake.acquire(); } catch (Throwable t) { Diag.log("freeze wake acquire failed", t); }
    }

    private void releaseWake() {
        if (wake != null && wake.isHeld()) {
            try { wake.release(); } catch (Throwable ignored) { }
        }
        wake = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------ foreground

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Pulse freeze",
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notification() {
        Intent open = new Intent(this, AppsActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, FreezeService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Freeze active")
                .setContentText("Holding frozen apps stopped")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Release all", ps).build())
                .setOngoing(true)
                .build();
    }

    /** Start the reaper if it is not already up. Safe to call repeatedly. */
    static void ensure(Context c) {
        Intent i = new Intent(c, FreezeService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }
}
