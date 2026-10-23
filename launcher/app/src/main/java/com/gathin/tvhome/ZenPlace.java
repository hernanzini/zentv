package com.gathin.tvhome;

import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * weather_city: a place written as people write it ("Resistencia, Chaco, Argentina") turned into coordinates for
 * the weather, with Open-Meteo's geocoding (geocoding-api.open-meteo.com, no account or key). Asked once and kept
 * in place.json, so the TV asks again only when the name changes. The first part is the place; the others narrow
 * it down to a province, state or country, so "Resistencia, Bolivia" is not the one in Argentina.
 */
final class ZenPlace {
    static final String FILE_NAME = "place.json";
    private static final int MAX_BYTES = 64 * 1024;

    /** The weather_city text this answers, as configured. */
    final String query;
    final double latitude;
    final double longitude;

    ZenPlace(String query, double latitude, double longitude) {
        this.query = query;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    static File file(File dir) {
        return new File(dir, FILE_NAME);
    }

    /** The search for the first part of the name, up to ten candidates, named in the TV's language. */
    static String url(String query, String language) {
        try {
            return "https://geocoding-api.open-meteo.com/v1/search?name="
                    + URLEncoder.encode(parts(query)[0], "UTF-8") + "&count=10&format=json&language="
                    + URLEncoder.encode(language.isEmpty() ? "en" : language, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);   // UTF-8 is always there
        }
    }

    /**
     * The first candidate (Open-Meteo lists the most likely first) whose province, state or country matches every
     * part after the first; null when none does or the answer has no candidates.
     */
    static ZenPlace pick(String query, JSONObject answer) {
        String[] parts = parts(query);
        JSONArray results = answer.optJSONArray("results");
        if (results == null) return null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || !(r.opt("latitude") instanceof Number) || !(r.opt("longitude") instanceof Number)) continue;
            String where = fold(r.optString("admin1") + " " + r.optString("admin2") + " " + r.optString("country")
                    + " " + r.optString("country_code"));
            boolean all = true;
            for (int p = 1; p < parts.length && all; p++) all = where.contains(fold(parts[p]));
            if (all) return new ZenPlace(query, r.optDouble("latitude"), r.optDouble("longitude"));
        }
        return null;
    }

    /** The kept answer for this name, or null when there is none or it was for another name. */
    static ZenPlace cached(File file, String query) {
        if (!file.isFile()) return null;
        try {
            JSONObject o = new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            if (!query.equals(o.optString("query"))) return null;
            return new ZenPlace(query, o.getDouble("latitude"), o.getDouble("longitude"));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Network, off the main thread: finds the place, keeps it in {@code file} and returns it. */
    static ZenPlace resolve(File file, String query, String language) throws IOException {
        String body = new String(ZenNet.get(url(query, language), MAX_BYTES), StandardCharsets.UTF_8);
        ZenPlace place;
        try {
            place = pick(query, new JSONObject(body));
        } catch (JSONException e) {
            throw new IOException("not JSON from geocoding-api.open-meteo.com");
        }
        if (place == null) throw new IOException("no place called \"" + query + "\"");
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            byte[] bytes = new JSONObject().put("query", query).put("latitude", place.latitude)
                    .put("longitude", place.longitude).toString().getBytes(StandardCharsets.UTF_8);
            out = af.startWrite();
            out.write(bytes);
            af.finishWrite(out);
        } catch (IOException | JSONException e) {
            // Only the cache is lost: asked again after the next restart.
            if (out != null) af.failWrite(out);
        }
        return place;
    }

    /** The comma-separated parts, trimmed, blanks dropped; at least one (possibly empty). */
    static String[] parts(String query) {
        String[] raw = query.split(",");
        int n = 0;
        for (int i = 0; i < raw.length; i++) {
            String s = raw[i].trim();
            if (!s.isEmpty()) raw[n++] = s;
        }
        if (n == 0) return new String[]{""};
        String[] out = new String[n];
        System.arraycopy(raw, 0, out, 0, n);
        return out;
    }

    /** Lower case without accents, so "Peru" matches "Perú" and "chaco" matches "Provincia del Chaco". */
    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
