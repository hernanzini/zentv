package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * ZenTV: opens one app when the TV is switched on onto Home (zen_autostart_app), for sets used mostly for a
 * streaming live-TV app such as Sensa. Upstream's boot_to_live_tv opens the Live TV app at boot, at once. This also
 * covers waking from standby, sends optional deep links on a fresh boot (zen_autostart_uri), and waits for a
 * validated network first, up to 20 s: Ethernet and Wi-Fi come back seconds after the screen, and a streaming app
 * started offline shows an error. The first link opens the app; the others follow STEP_MS apart, and the last one
 * repeats until the app starts playing (STEPS_MS at most). Sensa: minerva://content/livetv opens the guide on the
 * last channel and minerva://keycode/dpad_center presses OK on it, which it only takes once the guide has loaded,
 * 17 to 31 s after a cold start on an RCA UnionTV. One OK too many matters: during playback it opens the player
 * panel on Pause, and the next one pauses the channel. So while the links go out, Home holds the audio focus, and
 * the app taking it (which a player does as it starts, a second or so in) stops them at once; the sound itself
 * comes about 12 s later.
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
    /** How long a boot or wake-up waits for a validated network before it opens the app anyway. */
    private static final long NETWORK_WAIT_MS = 20_000;
    /** Between one deep link and the next, and between repeats of the last one. */
    private static final long STEP_MS = 3_000;
    /** The later deep links stop this long after the first, playing or not: Sensa's guide took 31 s right after a
     *  reboot of an RCA UnionTV, while the TV was still busy starting its other apps. */
    private static final long STEPS_MS = 60_000;

    // Static so they survive the activity being recreated; they only reset when the process dies.
    /** When the screen last came on (elapsedRealtime), or 0 once that wake-up has been handled. */
    private static long screenOnAt;
    private static boolean bootHandled;

    private final Activity activity;
    private final Handler handler;
    private final String app;
    /** zen_autostart_uri: the deep links for a fresh boot, in order; empty opens the app's launch intent. */
    private final List<String> uris;
    private boolean resumed;
    /** The app's intent while it waits for the network. */
    private Intent pending;
    private long networkDeadline;
    /** The deep links after the first, while they are being sent; null when there are none to send. */
    private List<String> steps;
    private int step;
    /** Links sent since the app was opened, the first included: for the log. */
    private int sent;
    private long stepsDeadline;
    private final Runnable autostart = this::maybeAutostart;
    private final Runnable openWhenOnline = this::openWhenOnline;
    private final Runnable nextStep = this::nextStep;
    /** Held while the links go out, so the app's player taking it says that it has started. */
    private AudioFocusRequest focus;

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

    ZenAutostart(Activity activity, Handler handler, String app, List<String> uris) {
        this.activity = activity;
        this.handler = handler;
        this.app = app;
        this.uris = uris;
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
        stopSteps();   // Home is back: the owner left the app, so no more keys go to it
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
        stopSteps();
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
        steps = null;
        if (boot && !uris.isEmpty()) {
            i = link(uris.get(0));
            if (i != null && uris.size() > 1) steps = uris.subList(1, uris.size());
        }
        if (i == null) i = activity.getPackageManager().getLeanbackLaunchIntentForPackage(app);
        if (i == null) i = activity.getPackageManager().getLaunchIntentForPackage(app);
        if (i == null) return;
        pending = i;
        networkDeadline = now + NETWORK_WAIT_MS;
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
        if (!start(i)) steps = null;
        if (steps == null) return;
        step = 0;
        sent = 1;
        stepsDeadline = SystemClock.elapsedRealtime() + STEPS_MS;
        holdFocus();
        handler.removeCallbacks(nextStep);
        handler.postDelayed(nextStep, STEP_MS);
    }

    /**
     * Sends the next deep link to the app, which is on screen now that Home is paused. The last one repeats until
     * the app plays sound: a cold-started app takes an unknown time to load, and a key it gets too early is lost.
     */
    private void nextStep() {
        List<String> s = steps;
        if (s == null) return;
        boolean playing = playing();
        if (playing || SystemClock.elapsedRealtime() > stepsDeadline) {
            Log.i(TAG, (playing ? "playing" : "gave up") + " after " + sent + " link(s)");
            stopSteps();
            return;
        }
        Intent i = link(s.get(step));
        if (i == null || !start(i)) {
            steps = null;
            return;
        }
        sent++;
        if (step < s.size() - 1) step++;
        handler.postDelayed(nextStep, STEP_MS);
    }

    private void stopSteps() {
        steps = null;
        handler.removeCallbacks(nextStep);
        releaseFocus();
    }

    /**
     * Takes a transient audio focus that ducks nothing (Home plays no sound). Any player that starts while the links
     * go out takes it back, and that loss is the moment to stop sending keys.
     */
    private void holdFocus() {
        AudioManager am = activity.getSystemService(AudioManager.class);
        if (am == null) return;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(change -> {
                    if (change < 0 && steps != null) {
                        Log.i(TAG, "playing (audio focus) after " + sent + " link(s)");
                        stopSteps();
                    }
                }, handler)
                .build();
        if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = null;
    }

    private void releaseFocus() {
        AudioManager am = activity.getSystemService(AudioManager.class);
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
        focus = null;
    }

    /** A deep link for the app, sent to its running task (onNewIntent); null when the app does not take it. */
    private Intent link(String uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(app);
        if (i.resolveActivity(activity.getPackageManager()) == null) return null;
        return i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private boolean start(Intent i) {
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot open " + app + ": " + e);
            return false;
        }
    }

    /** Something plays sound: a fallback for a player that never asks for the audio focus. */
    private boolean playing() {
        AudioManager am = activity.getSystemService(AudioManager.class);
        return am != null && am.isMusicActive();
    }

    private boolean networkValidated() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
