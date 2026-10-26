package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/**
 * ZenTV: opens one app when the TV is switched on onto Home (zen_autostart_app), for sets used mostly for a
 * streaming live-TV app such as Sensa. Upstream's boot_to_live_tv opens the Live TV app at boot, at once. This also
 * covers waking from standby, sends an optional deep link on a fresh boot (zen_autostart_uri), and waits for a
 * validated network first: a streaming app started offline shows an error. After standby, Ethernet and Wi-Fi come
 * back seconds after the screen, so the wait is up to 20 s. After a boot it is up to 5 minutes: when the power
 * comes back after a cut, the router starts again too, and the TV's first Wi-Fi scans look only on the channel it
 * used last. Sensa started offline shows "no connection" after 30 s and, on retry, asks for the profile instead of
 * logging in by itself.
 *
 * Only a link, never keys. Sensa plays the last channel at start by itself when its own "tune to live" option is
 * on, so it needs no link at all; minerva://content/livetv opens its guide on the last channel. Pressing OK in the
 * guide by deep link (minerva://keycode/dpad_center) did not hold after a boot: a key that came before the guide
 * had loaded landed on Sensa's home screen and opened a live sports event. Its channel_up "from outside" plays the
 * last channel only once Sensa has played something since it started, so not after a boot either.
 *
 * Waking from standby needs Home's process alive while the app is on screen, which ZenKeepAliveService (a
 * foreground service) keeps, and ZenHoldActivity holds the app until the network is back. Both run only while an
 * app is set. A wake-up into another app or an HDMI input (HDMI-CEC) is left alone, and Home stays Home when the
 * remote's Home button is pressed later.
 */
final class ZenAutostart {
    private static final String TAG = "ZenTv.Autostart";
    /** A resume this soon after the screen came on is the wake-up's. */
    private static final long WAKE_WINDOW_MS = 10_000;
    /** The first Home this soon after boot is the boot's. */
    private static final long BOOT_WINDOW_MS = 180_000;
    /** How long a wake-up waits for a validated network before it opens the app anyway. */
    private static final long NETWORK_WAIT_MS = 20_000;
    /** The same for a boot. Below the screensaver's usual 10 minutes, which would take Home off screen. */
    private static final long BOOT_NETWORK_WAIT_MS = 300_000;

    // Static so they survive the activity being recreated; they only reset when the process dies.
    /** When the screen last came on (elapsedRealtime), or 0 once that wake-up has been handled. */
    private static long screenOnAt;
    private static boolean bootHandled;

    private final Activity activity;
    private final Handler handler;
    private final String app;
    private final String uri;
    private boolean resumed;
    /** The app's intent while it waits for the network. */
    private Intent pending;
    private long networkDeadline;
    private final Runnable autostart = this::maybeAutostart;
    private final Runnable openWhenOnline = this::openWhenOnline;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                // Hold the app on screen before the TV suspends, so the wake-up waits for the network before it
                // resumes. Putting Home on top instead makes it a background app, which TCL firmware force-stops
                // when the network comes back.
                if (!resumed) {
                    try {
                        activity.startActivity(new Intent(activity, ZenHoldActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                    } catch (RuntimeException e) {
                        // Refused (background activity start): the app simply resumes first.
                    }
                }
                return;
            }
            screenOnAt = SystemClock.elapsedRealtime();
            // Home was already on screen when the TV went to standby: onResume may not run again.
            if (resumed) {
                handler.removeCallbacks(autostart);
                handler.postDelayed(autostart, 1500);
            }
        }
    };

    ZenAutostart(Activity activity, Handler handler, String app, String uri) {
        this.activity = activity;
        this.handler = handler;
        this.app = app;
        this.uri = uri;
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(screenReceiver, screen, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(screenReceiver, screen);
        }
        // Started from the foreground (onCreate of the visible Home), so background start limits don't apply.
        try {
            activity.startForegroundService(new Intent(activity, ZenKeepAliveService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "no keep-alive service: " + e);   // Home works; the TV's cleaner may just stop it sooner
        }
    }

    void resume() {
        resumed = true;
        handler.removeCallbacks(autostart);
        handler.postDelayed(autostart, 500);
    }

    void pause() {
        resumed = false;
        handler.removeCallbacks(autostart);
        handler.removeCallbacks(openWhenOnline);
        pending = null;
    }

    void close() {
        pause();
        try {
            activity.unregisterReceiver(screenReceiver);
        } catch (RuntimeException ignored) {
            // not registered
        }
    }

    /** Opens the app when Home is what the TV shows right after it boots or wakes up from standby. */
    private void maybeAutostart() {
        if (!resumed) return;
        long now = SystemClock.elapsedRealtime();
        boolean wake = screenOnAt != 0 && now - screenOnAt < WAKE_WINDOW_MS;
        boolean boot = !bootHandled && now < BOOT_WINDOW_MS;
        bootHandled = true;
        screenOnAt = 0;
        if (!wake && !boot) return;
        Intent i = null;
        // After a wake-up the app is usually still running where it was (on the channel that was on), so it is
        // only brought back; the deep link is for a fresh start.
        if (boot && !uri.isEmpty()) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(app);
            if (i.resolveActivity(activity.getPackageManager()) == null) {
                i = null;
            } else {
                // Reuse the app's running task, so the deep link reaches it through onNewIntent.
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
        }
        if (i == null) i = activity.getPackageManager().getLeanbackLaunchIntentForPackage(app);
        if (i == null) i = activity.getPackageManager().getLaunchIntentForPackage(app);
        if (i == null) return;
        Log.i(TAG, (boot ? "boot" : "wake-up") + ": opening " + app + (i.getData() != null ? " with " + i.getData() : ""));
        pending = i;
        networkDeadline = now + (boot ? BOOT_NETWORK_WAIT_MS : NETWORK_WAIT_MS);
        handler.removeCallbacks(openWhenOnline);
        openWhenOnline();
    }

    /** Opens the pending app once the network is validated, or when the wait is over. Leaving Home cancels it. */
    private void openWhenOnline() {
        Intent i = pending;
        if (i == null || !resumed) {
            pending = null;
            return;
        }
        if (!networkValidated() && SystemClock.elapsedRealtime() < networkDeadline) {
            handler.postDelayed(openWhenOnline, 500);
            return;
        }
        pending = null;
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot open " + app + ": " + e);
        }
    }

    private boolean networkValidated() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
