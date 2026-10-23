package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ZenTV's settings, read beside {@link Config} so that upstream class stays as it is: the zen_* values in
 * res/values/zen_config.xml, overlaid by the same keys without the prefix in a theme's config.json.
 *
 * The overlay is read from the copy Config.load keeps in the app's files directory (it mirrors the theme's
 * config.json, and stands in for it before external storage is mounted), so load this after Config.load.
 */
final class ZenConfig {
    private static final String TAG = "ZenTv.Config";

    /** The weather line under the header (zen_weather). */
    final boolean weather;
    /**
     * Where the weather is for, in this order: zen_weather_latitude / _longitude; else zen_weather_city, a place
     * name the TV looks up once (ZenPlace), with NaN here; else config.xml's latitude / longitude (the sky's).
     */
    final double latitude;
    final double longitude;
    /** zen_weather_city when it is the one in use (no coordinates were given), else empty. */
    final String city;
    /** Bing's picture of the day behind Home, downloaded once a day (zen_daily_wallpaper). */
    final boolean dailyWallpaper;
    /** App opened when the TV boots or wakes up onto Home (zen_autostart_app); empty = off. */
    final String autostartApp;
    /** The deep links sent to it on a fresh boot, in order (zen_autostart_uri); see ZenAutostart. */
    final List<String> autostartUris;

    ZenConfig(boolean weather, double latitude, double longitude, String city, boolean dailyWallpaper,
              String autostartApp, List<String> autostartUris) {
        boolean located = !Double.isNaN(latitude) && !Double.isNaN(longitude);
        this.city = located ? "" : city.trim();
        this.weather = weather && (located || !this.city.isEmpty());
        this.latitude = located ? latitude : Double.NaN;
        this.longitude = located ? longitude : Double.NaN;
        this.dailyWallpaper = dailyWallpaper;
        this.autostartApp = autostartApp.trim();
        this.autostartUris = this.autostartApp.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(autostartUris));
    }

    /** Anything here uses the network. */
    boolean online() {
        return weather || dailyWallpaper;
    }

    static ZenConfig load(Context ctx) {
        Resources res = ctx.getResources();
        return from(read(new File(ctx.getFilesDir(), Config.FILE_NAME)),
                res.getBoolean(R.bool.zen_weather),
                coordinate(res.getString(R.string.zen_weather_latitude), Double.NaN, 90),
                coordinate(res.getString(R.string.zen_weather_longitude), Double.NaN, 180),
                res.getString(R.string.zen_weather_city),
                coordinate(res.getString(R.string.latitude), Double.NaN, 90),
                coordinate(res.getString(R.string.longitude), Double.NaN, 180),
                res.getBoolean(R.bool.zen_daily_wallpaper),
                res.getString(R.string.zen_autostart_app),
                links(res.getString(R.string.zen_autostart_uri)));
    }

    /**
     * config.xml's values, overlaid by a theme's keys; any key missing or of the wrong type keeps its value. The
     * sky's coordinates are the place only when neither coordinates nor a city were given for the weather.
     */
    static ZenConfig from(JSONObject json, boolean weather, double latitude, double longitude, String city,
                          double skyLatitude, double skyLongitude, boolean dailyWallpaper,
                          String autostartApp, List<String> autostartUris) {
        if (json != null) {
            weather = json.opt("weather") instanceof Boolean ? json.optBoolean("weather") : weather;
            latitude = decimal(json.opt("weather_latitude"), latitude, 90);
            longitude = decimal(json.opt("weather_longitude"), longitude, 180);
            city = json.opt("weather_city") instanceof String ? json.optString("weather_city") : city;
            dailyWallpaper = json.opt("daily_wallpaper") instanceof Boolean ? json.optBoolean("daily_wallpaper") : dailyWallpaper;
            autostartApp = json.opt("autostart_app") instanceof String ? json.optString("autostart_app") : autostartApp;
            Object uri = json.opt("autostart_uri");
            if (uri instanceof String) autostartUris = links((String) uri);
            else if (uri instanceof JSONArray) autostartUris = links((JSONArray) uri);
        }
        if ((Double.isNaN(latitude) || Double.isNaN(longitude)) && city.trim().isEmpty()) {
            latitude = skyLatitude;
            longitude = skyLongitude;
        }
        return new ZenConfig(weather, latitude, longitude, city, dailyWallpaper, autostartApp, autostartUris);
    }

    /** Deep links written in one string, separated by spaces ("minerva://content/livetv minerva://keycode/ok"). */
    static List<String> links(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.trim().split("\\s+")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** Deep links as a JSON array; entries that are not text are skipped. */
    static List<String> links(JSONArray array) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (item instanceof String) out.addAll(links((String) item));
        }
        return out;
    }

    /** A latitude or longitude from config.xml ("54.69"); the fallback when empty, not a number or out of range. */
    static double coordinate(String text, double fallback, double limit) {
        try {
            double d = Double.parseDouble(text.trim());
            return Math.abs(d) <= limit ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double decimal(Object value, double fallback, double limit) {
        if (!(value instanceof Number)) return fallback;
        double d = ((Number) value).doubleValue();
        return Math.abs(d) <= limit ? d : fallback;
    }

    /** Any problem means "no overlay": Home never fails over a bad file. */
    private static JSONObject read(File file) {
        if (!file.isFile() || file.length() > 64 * 1024) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }
}
