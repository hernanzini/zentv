package com.gathin.tvhome;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Held over the app on screen while the TV is in standby, so at wake-up that app resumes only once the network
 * is back. Ethernet and Wi-Fi return a few seconds after the screen, and an app that resumes first fails without
 * a network (a streaming app shows an error and a retry button). The activity is see-through, so the app stays visible
 * behind it: TV firmware that force-stops background apps when the network returns (TCL's
 * com.tcl.framework.custom) leaves it alone, which it does not when Home is put on top.
 *
 * ZenTV: ZenAutostart starts it when the screen goes off and another app is on top (zen_autostart_app). It finishes
 * once the network is validated, after NETWORK_WAIT_MS, on any key, or as soon as anything else takes the screen
 * (an HDMI input switched on through HDMI-CEC).
 */
public class ZenHoldActivity extends Activity {
    private static final long NETWORK_WAIT_MS = 20_000;
    /** After validation: lets the TV's own wake-up clean-up pass while the app is still visible. */
    private static final long SETTLE_MS = 1_000;
    /** Only show "Connecting…" when the wait is long enough to notice. */
    private static final long LABEL_DELAY_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView label;
    private long startedAt;
    private long validatedAt;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (!interactive()) return;   // back in standby: wait for the next wake-up
            long now = SystemClock.elapsedRealtime();
            if (networkValidated()) {
                if (validatedAt == 0) validatedAt = now;
                if (now - validatedAt >= SETTLE_MS) {
                    done();
                    return;
                }
            } else if (now - startedAt >= NETWORK_WAIT_MS) {
                done();
                return;
            } else if (now - startedAt >= LABEL_DELAY_MS) {
                label.setVisibility(View.VISIBLE);
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float density = getResources().getDisplayMetrics().density;
        label = new TextView(this);
        label.setText(R.string.zen_connecting);
        label.setTextSize(15f);
        label.setTextColor(0xFFFFFFFF);
        try {
            label.setTypeface(UiFont.get(this, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        label.setBackgroundResource(R.drawable.pill_bg);
        int padH = (int) (20 * density), padV = (int) (10 * density);
        label.setPadding(padH, padV, padH, padV);
        label.setVisibility(View.INVISIBLE);
        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (48 * density);
        root.addView(label, lp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Resumed while the TV is still going to sleep: nothing to do until it wakes up.
        if (!interactive()) return;
        startedAt = SystemClock.elapsedRealtime();
        validatedAt = 0;
        handler.removeCallbacks(check);
        handler.post(check);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(check);
        // Something else took the screen after the wake-up: the hold is over.
        if (interactive() && !isFinishing()) done();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        done();
        return true;
    }

    private void done() {
        if (isFinishing()) return;
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean interactive() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private boolean networkValidated() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
