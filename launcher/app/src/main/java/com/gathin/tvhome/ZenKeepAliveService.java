package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * A foreground service with nothing to do but keep Home's process alive while another app is on screen.
 * ZenTV: ZenAutostart reacts to the screen going off and on (zen_autostart_app), and TV firmware that cleans
 * up memory (TCL's com.tcl.framework.custom) force-stops background apps about 30 s after the foreground app
 * changes, which takes those receivers with it. Started only when an autostart app is set.
 */
public class ZenKeepAliveService extends Service {
    private static final String CHANNEL = "keep_alive";

    @Override
    public void onCreate() {
        super.onCreate();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_MIN));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        Notification n = b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(getString(R.string.app_name))
                .setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (RuntimeException e) {
            // Refused (background start limits): Home works as before, only without the keep-alive.
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
