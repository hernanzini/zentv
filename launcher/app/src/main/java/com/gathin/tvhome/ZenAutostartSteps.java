package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ZenTV (zen_autostart_steps): after a boot, sends the app ZenAutostart opened a few deep links, each one only once a
 * given view of that app has focus, so the link reaches the screen it is meant for. Sensa opens its guide on the
 * last channel at boot but never plays by itself, and its Play-key link (minerva://keycode/media_play) plays the
 * marked channel only once the guide has loaded: sent blind, a key lands on whatever screen Sensa shows then. Here
 * the link waits for the guide's view ("grid") to have focus.
 *
 * An accessibility service, because nothing else can see another app's views. It reads the id of the focused view
 * and of its parents, nothing else; it listens only for WINDOW_MS after ZenAutostart armed it at a boot, never after
 * a wake-up from standby. It does not ask for touch exploration, which apps (Sensa) take as TalkBack being on.
 * Off until the person turns it on (docs/zentv/CONFIG.md).
 */
public class ZenAutostartSteps extends AccessibilityService {
    private static final String TAG = "ZenTv.Steps";
    /** How long after the boot's autostart the steps may run. */
    static final long WINDOW_MS = 180_000;
    /** A focus that lasts this long is the app's, not a step of an animation or of loading. */
    private static final long SETTLE_MS = 1_500;
    /**
     * Between two links the app gets this long to change screens: Sensa takes about 4 s from its side menu to the
     * guide, and its guide link opens the menu again when the guide already shows. The last step's link goes again
     * once when its view still has focus this long after it.
     */
    private static final long GAP_MS = 5_000;
    /** At most this many links per boot, whatever the app does. */
    private static final int MAX_LINKS = 6;

    /** One "view:link" entry: the link goes to the app when the view with this id, or one inside it, has focus. */
    static final class Step {
        final String view;
        final String link;

        Step(String view, String link) {
            this.view = view;
            this.link = link;
        }
    }

    // Armed by ZenAutostart, in the same process, when it opens the app at a boot; cleared when done.
    private static volatile String armedApp;
    private static volatile List<Step> armedSteps = Collections.emptyList();
    private static volatile long armedUntil;
    private static int linksSent;
    private static int lastStepSent;
    private static long lastLinkAt;
    /** The connected service, if any, so that arming can start the events. */
    private static ZenAutostartSteps live;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = this::check;

    /** Called on the main thread when ZenAutostart opens app at a boot. */
    static void arm(String app, List<String> entries) {
        List<Step> steps = parse(entries);
        if (steps.isEmpty()) return;
        armedApp = app;
        armedSteps = steps;
        armedUntil = SystemClock.elapsedRealtime() + WINDOW_MS;
        linksSent = 0;
        lastStepSent = 0;
        lastLinkAt = 0;
        if (live != null) live.listen();
    }

    /** "view:link" entries, split at the first colon; an entry without both parts is skipped. */
    static List<Step> parse(List<String> entries) {
        List<Step> out = new ArrayList<>();
        for (String e : entries) {
            int colon = e.indexOf(':');
            if (colon <= 0) continue;
            String view = e.substring(0, colon).trim();
            String link = e.substring(colon + 1).trim();
            if (!view.isEmpty() && !link.isEmpty()) out.add(new Step(view, link));
        }
        return out;
    }

    /**
     * The step for the focused view, or -1: ids holds the focused view's id first, then its parents' ("pkg:id/name"
     * or null). Only app's own views count: Home has a "grid" too, and stays the active window for a moment after
     * the app opens. The nearest view wins, so a step for a container covers the views inside it.
     */
    static int match(List<Step> steps, String app, List<String> ids) {
        for (String id : ids) {
            if (id == null) continue;
            for (int i = 0; i < steps.size(); i++) {
                if (id.equals(app + ":id/" + steps.get(i).view)) return i;
            }
        }
        return -1;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        live = this;
        listen();
    }

    @Override
    public void onDestroy() {
        if (live == this) live = null;
        handler.removeCallbacks(check);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!armed()) {
            disarm();
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !armedApp.contentEquals(pkg)) return;
        handler.removeCallbacks(check);
        handler.postDelayed(check, SETTLE_MS);
    }

    @Override
    public void onInterrupt() {
        // No feedback to stop.
    }

    private static boolean armed() {
        return armedApp != null && SystemClock.elapsedRealtime() < armedUntil;
    }

    /** Events from the armed app while armed, none at all otherwise. */
    private void listen() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        boolean on = armed();
        info.eventTypes = on ? AccessibilityEvent.TYPE_VIEW_FOCUSED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : 0;
        info.packageNames = on ? new String[]{armedApp} : null;
        setServiceInfo(info);
        if (on) {
            handler.removeCallbacks(check);
            handler.postDelayed(check, SETTLE_MS);   // the app may already show the view
        }
    }

    private void disarm() {
        if (armedApp == null) return;
        armedApp = null;
        armedSteps = Collections.emptyList();
        handler.removeCallbacks(check);
        listen();
    }

    private void check() {
        if (!armed()) {
            disarm();
            return;
        }
        List<Step> steps = armedSteps;
        int i = match(steps, armedApp, focusedIds());
        boolean last = i == steps.size() - 1;
        if (i < 0 || (last && lastStepSent >= 2) || linksSent >= MAX_LINKS) {
            // Nothing to do on this screen; once the last link went, the app has left the view: done.
            if (lastStepSent > 0 || linksSent >= MAX_LINKS) disarm();
            return;
        }
        long wait = lastLinkAt + GAP_MS - SystemClock.elapsedRealtime();
        if (lastLinkAt != 0 && wait > 0) {
            handler.postDelayed(check, wait);
            return;
        }
        Log.i(TAG, "focus on " + steps.get(i).view + ": sending " + steps.get(i).link);
        linksSent++;
        lastLinkAt = SystemClock.elapsedRealtime();
        if (last) lastStepSent++;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(steps.get(i).link)).setPackage(armedApp)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "cannot send " + steps.get(i).link + ": " + e);
            disarm();
            return;
        }
        if (last) handler.postDelayed(check, GAP_MS);
    }

    /** The focused view's id, then its parents', in the active window. */
    private List<String> focusedIds() {
        List<String> ids = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo node = root != null ? root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) : null;
        for (int depth = 0; node != null && depth < 12; depth++) {
            ids.add(node.getViewIdResourceName());
            node = node.getParent();
        }
        return ids;
    }
}
